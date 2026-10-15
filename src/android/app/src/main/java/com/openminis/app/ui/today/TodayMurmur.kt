package com.openminis.app.ui.today

import android.content.Context
import com.openminis.app.data.journal.CheckIns
import com.openminis.app.data.journal.JournalEntry
import com.openminis.app.data.journal.JournalStore
import com.openminis.app.data.murmur.DishRules
import com.openminis.app.data.murmur.MoodTier
import com.openminis.app.data.murmur.MurmurCloud
import com.openminis.app.data.murmur.MurmurIndex
import com.openminis.app.data.murmur.MurmurLabels
import com.openminis.app.data.murmur.MurmurLlm
import com.openminis.app.data.murmur.MurmurReminder
import com.openminis.app.data.murmur.MurmurSuggestion
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** One murmur on the mood page (§19.2): its colour, and whether the user set it. */
data class MoodNote(val id: String, val date: LocalDate, val atMs: Long, val body: String, val mood: String?, val byUser: Boolean) {
    val tier: MoodTier? get() = mood?.let(MoodTier::of)
}

data class MoodDay(val date: LocalDate, val tier: MoodTier?, val notes: List<MoodNote>)

/**
 * A murmur about food (§19.6): the murmur itself is what 我的菜单 shows —
 * its words and photos — with the dishes it names under it.
 */
data class FoodNote(
    val id: String,
    val date: LocalDate,
    val atMs: Long,
    val body: String,
    val photos: List<String>,
    /** Dish names, each with whether the murmur is about making it. */
    val dishes: List<Pair<String, Boolean>>,
) {
    val homemade: Boolean get() = dishes.any { it.second }
}

/**
 * [T-today-murmur] Today's side of the 碎碎念 model (SPEC §19): tags the
 * murmurs on screen in the background, colours the days (§19.2), and — when
 * 智能建议 is on — asks the cloud about the few that may need doing (§19.3).
 *
 * Runs in its own job like species ID: a pass never holds up the timeline,
 * and a reload meanwhile only queues the newest days for the next pass.
 */
internal class TodayMurmur(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: JournalStore,
    private val publish: (dayMood: Map<LocalDate, MoodTier>, noteMood: Map<String, String>, suggestions: Map<String, MurmurSuggestion>) -> Unit,
    private val reload: () -> Unit,
) {
    private val index = MurmurIndex.get(context)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private var job: Job? = null
    private var pending: List<TodayDay>? = null
    private var lastDays: List<TodayDay> = emptyList()
    private var retryJob: Job? = null
    /** Asked the cloud this session (ok or not): never twice for the same text. */
    private val asked = HashSet<String>()

    val ready: Boolean get() = MurmurLlm.isReady(context)
    var moodColors: Boolean
        // Off by default (author, 2026-10-15: a mood per murmur adds little) — the switch stays.
        get() = prefs.getBoolean(KEY_MOOD, false)
        set(v) { prefs.edit().putBoolean(KEY_MOOD, v).apply() }
    var smartSuggest: Boolean
        get() = prefs.getBoolean(KEY_SUGGEST, false)
        set(v) { prefs.edit().putBoolean(KEY_SUGGEST, v).apply(); if (v) onDays(lastDays) }

    private fun notesOf(days: List<TodayDay>): List<TodayNote> =
        days.flatMap { d -> d.rows.filterIsInstance<TodayRow.Note>().map { it.note } }
            .filter { it.entry.id != null && it.entry.body.isNotBlank() && it.editable }

    /** After every timeline load: show what is known at once, then tag what is new. */
    fun onDays(days: List<TodayDay>) {
        lastDays = days
        publishFor(days)
        if (!ready) return
        pending = days
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                val d = pending ?: break
                pending = null
                pass(d)
            }
        }
    }

    private suspend fun pass(days: List<TodayDay>) {
        val now = System.currentTimeMillis()
        var settling = false
        // Newest first: the note just sent is coloured before last week's.
        for (n in notesOf(days).sortedByDescending { it.atMs }) {
            val id = n.entry.id!!
            if (index.tags(id, n.entry.body) != null) continue
            // Just written or edited: wait — it is often changed again right away (§19.1).
            val touched = (n.entry.edited ?: n.entry.at)?.toInstant()?.toEpochMilli() ?: 0L
            if (now - touched < SETTLE_MS) { settling = true; continue }
            val t = MurmurLlm.tag(context, n.entry.body) ?: continue
            withContext(Dispatchers.IO) { index.putTags(id, n.entry.body, t) }
            publishFor(days)
        }
        if (smartSuggest) suggest(days)
        if (settling) {
            retryJob?.cancel()
            retryJob = scope.launch { delay(SETTLE_MS); reload() }
        }
    }

    /** §19.3: candidates from the last day only, once each, written by the user. */
    private suspend fun suggest(days: List<TodayDay>) {
        val since = System.currentTimeMillis() - SUGGEST_WINDOW_MS
        for (n in notesOf(days).filter { it.atMs >= since && !it.entry.writtenByMinis }.sortedByDescending { it.atMs }) {
            val id = n.entry.id!!
            val body = n.entry.body
            val tags = index.tags(id, body) ?: continue
            if (index.suggestion(id, body) != null || !asked.add(id + MurmurIndex.hash(body))) continue
            if (!MurmurLabels.isCandidate(body, tags)) continue
            val s = MurmurCloud.ask(context, body, n.atMs, MurmurIndex.hash(body)) ?: continue
            withContext(Dispatchers.IO) { index.putSuggestion(id, s) }
            publishFor(days)
        }
    }

    private fun publishFor(days: List<TodayDay>) {
        val dayMood = if (!moodColors) emptyMap() else days.mapNotNull { d ->
            val tiers = d.rows.filterIsInstance<TodayRow.Note>().mapNotNull { moodOf(it.note.entry)?.let(MoodTier::of) }
            MoodTier.ofDay(tiers)?.let { d.date to it }
        }.toMap()
        val noteMood = if (!moodColors) emptyMap() else notesOf(days).mapNotNull { n -> moodOf(n.entry)?.let { n.entry.id!! to it } }.toMap()
        publish(dayMood, noteMood, visibleSuggestions(days))
    }

    /** The user's own word first (§19.2), else the model's. */
    private fun moodOf(e: JournalEntry): String? = userMood(e) ?: e.id?.let { index.tags(it, e.body)?.mood }

    /** `mood=` as written by the user: one of the mood words (older builds wrote 明亮/平常/低沉). */
    private fun userMood(e: JournalEntry): String? = e.attrs[MOOD_ATTR]?.takeIf { it in MurmurLabels.MOODS || MoodTier.fromLabel(it) != null }

    /**
     * What shows under murmurs: reminders set, and today's open offers — at
     * most [MAX_OFFERS_A_DAY], newest kept. Yesterday's unanswered ones fade.
     */
    private fun visibleSuggestions(days: List<TodayDay>): Map<String, MurmurSuggestion> {
        if (!smartSuggest) return emptyMap()
        val today = LocalDate.now(zone)
        val out = HashMap<String, MurmurSuggestion>()
        val offers = ArrayList<Pair<String, MurmurSuggestion>>()
        for (n in notesOf(days)) {
            val id = n.entry.id!!
            val s = index.suggestion(id, n.entry.body) ?: continue
            when {
                // Shown until the end of the day it rings on ("已提醒" once past), then it goes quietly.
                s.state == MurmurSuggestion.SCHEDULED && !Instant.ofEpochMilli(s.atMs).atZone(zone).toLocalDate().isBefore(today) -> out[id] = s
                s.state == MurmurSuggestion.OFFER && Instant.ofEpochMilli(s.createdMs).atZone(zone).toLocalDate() == today -> offers += id to s
            }
        }
        offers.sortedByDescending { it.second.createdMs }.take(MAX_OFFERS_A_DAY).forEach { (id, s) -> out[id] = s }
        return out
    }

    // ── What the user does with a suggestion ───────────────────────────────

    /** Apply [change]; returns the suggestion as it was, for undo. */
    private fun update(note: TodayNote, change: (MurmurSuggestion) -> MurmurSuggestion): MurmurSuggestion? {
        val id = note.entry.id ?: return null
        val s = index.suggestion(id, note.entry.body) ?: return null
        val next = change(s)
        index.putSuggestion(id, next)
        syncAlarm(id, next)
        publishFor(lastDays)
        return s
    }

    /** The alarm always follows the stored state: set while SCHEDULED, gone otherwise. */
    private fun syncAlarm(id: String, s: MurmurSuggestion) {
        if (s.state == MurmurSuggestion.SCHEDULED) MurmurReminder.schedule(context, id, s.atMs) else MurmurReminder.cancel(context, id)
    }

    fun acceptReminder(note: TodayNote): MurmurSuggestion? =
        update(note) { it.copy(state = MurmurSuggestion.SCHEDULED) }.also { if (it != null) AppLogger.info(TAG, "reminder set") }

    /** A new time for an offered or set reminder (the cloud's guess is only a guess). */
    fun setReminderTime(note: TodayNote, atMs: Long): MurmurSuggestion? = update(note) { it.copy(atMs = atMs) }

    /** "不用", or cancelling a reminder that was set. Undo puts it back as it was. */
    fun dismiss(note: TodayNote): MurmurSuggestion? = update(note) { it.copy(state = MurmurSuggestion.DISMISSED) }

    fun restore(note: TodayNote, previous: MurmurSuggestion) {
        update(note) { previous }
    }

    /** Whether any model can take the cloud's second look; without one the switch says so (§19.3). */
    fun cloudAvailable(): Boolean = MurmurCloud.hasModel(context)

    /** The one-time "the dot is the day's mood" hint (first time colours appear). */
    var moodHintShown: Boolean
        get() = prefs.getBoolean(KEY_MOOD_HINT, false)
        set(v) { prefs.edit().putBoolean(KEY_MOOD_HINT, v).apply() }

    // ── Mood page (§19.2) ─────────────────────────────────────────────────

    suspend fun month(ym: YearMonth): List<MoodDay> {
        val days = ArrayList<MoodDay>()
        val untagged = ArrayList<JournalEntry>()
        for (d in 1..ym.lengthOfMonth()) {
            val date = ym.atDay(d)
            val entries = store.readDay(date)?.entries.orEmpty()
                .filter { it.id != null && it.body.isNotBlank() && !CheckIns.isCheckIn(it) }
            val notes = entries.map { e ->
                val user = userMood(e)
                if (user == null && index.tags(e.id!!, e.body) == null) untagged += e
                MoodNote(e.id!!, date, e.at?.toInstant()?.toEpochMilli() ?: 0L, e.body, user ?: index.tags(e.id!!, e.body)?.mood, user != null)
            }
            days += MoodDay(date, MoodTier.ofDay(notes.mapNotNull { it.tier }), notes)
        }
        lastMonthUntagged = untagged
        return days
    }

    private var lastMonthUntagged: List<JournalEntry> = emptyList()

    /** Older murmurs the timeline never loaded: tag them while the page is open. */
    suspend fun tagMonthBacklog(onEach: () -> Unit) {
        if (!ready) return
        for (e in lastMonthUntagged) {
            val t = MurmurLlm.tag(context, e.body) ?: continue
            withContext(Dispatchers.IO) { index.putTags(e.id!!, e.body, t) }
            onEach()
        }
        lastMonthUntagged = emptyList()
        publishFor(lastDays)
    }

    // ── 我的菜单 (§19.6) ──────────────────────────────────────────────────

    private var backlog: List<JournalEntry> = emptyList()

    /**
     * Every murmur about food, newest first: one that names a dish, or whose
     * main subject the model read as 吃喝. Murmurs not tagged yet are kept
     * for [tagBacklog]. [days] = the journal already read, if the caller has it.
     */
    suspend fun foodNotes(days: List<Pair<LocalDate, List<JournalEntry>>>? = null): List<FoodNote> = withContext(Dispatchers.IO) {
        val out = ArrayList<FoodNote>()
        val untagged = ArrayList<JournalEntry>()
        val source = days ?: store.availableDays().map { it to store.readDay(it)?.entries.orEmpty() }
        for ((date, all) in source.sortedByDescending { it.first }) {
            val entries = all
                .filter { it.id != null && it.body.isNotBlank() && !CheckIns.isCheckIn(it) }
                .sortedByDescending { it.at?.toInstant()?.toEpochMilli() ?: 0L }
            for (e in entries) {
                val tags = index.tags(e.id!!, e.body)
                if (tags == null) { untagged += e; continue }
                val names = DishRules.dishes(tags.mentions.filter { it.first == "吃喝" }.map { it.second }, e.body)
                if (names.isEmpty() && tags.aspects.firstOrNull() != "吃喝") continue
                val dishes = names.map { it to DishRules.homemade(e.body, it) }
                out += FoodNote(
                    e.id!!, date, e.at?.toInstant()?.toEpochMilli() ?: date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    e.body, e.photos, dishes,
                )
            }
        }
        backlog = untagged
        out
    }

    /** Tag the murmurs [foodNotes] found untagged, newest first; [onProgress] after each (done, total). */
    suspend fun tagBacklog(onProgress: (Int, Int) -> Unit) {
        if (!ready) return
        val todo = backlog
        backlog = emptyList()
        todo.forEachIndexed { i, e ->
            MurmurLlm.tag(context, e.body)?.let { t -> withContext(Dispatchers.IO) { index.putTags(e.id!!, e.body, t) } }
            onProgress(i + 1, todo.size)
        }
    }

    suspend fun setMood(date: LocalDate, id: String, word: String) {
        store.setAttrs(date, id, mapOf(MOOD_ATTR to word))
    }

    companion object {
        private const val TAG = "TodayMurmur"
        private const val PREFS = "today_murmur"
        private const val KEY_MOOD = "moodColors"
        private const val KEY_SUGGEST = "smartSuggest"
        private const val KEY_MOOD_HINT = "moodHintShown"
        /** A user's own mood colour, written into the entry (§19.2). */
        const val MOOD_ATTR = "mood"
        private const val SETTLE_MS = 15_000L
        private const val SUGGEST_WINDOW_MS = 24 * 60 * 60 * 1000L
        private const val MAX_OFFERS_A_DAY = 3

    }
}
