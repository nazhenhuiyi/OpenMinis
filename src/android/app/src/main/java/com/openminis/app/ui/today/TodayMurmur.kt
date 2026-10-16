package com.openminis.app.ui.today

import android.content.Context
import com.openminis.app.data.journal.CheckIns
import com.openminis.app.data.journal.JournalEntry
import com.openminis.app.data.journal.JournalStore
import com.openminis.app.data.murmur.DishRules
import com.openminis.app.data.murmur.MoodTier
import com.openminis.app.data.murmur.MurmurAgent
import com.openminis.app.data.murmur.MurmurIndex
import com.openminis.app.data.murmur.MurmurLabels
import com.openminis.app.data.murmur.MurmurLlm
import com.openminis.app.data.murmur.MurmurSuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    /** The dishes it names, cleaned of ingredients ([DishRules]). */
    val dishes: List<String>,
)

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
    /** Minis working on murmurs in the background (§19.3), one at a time. */
    private var agentJob: Job? = null
    private val _working = MutableStateFlow<String?>(null)
    /** The murmur Minis is looking at right now, if any: "Minis 正在看这条…" under it. */
    val working: StateFlow<String?> = _working
    /** The day old background chats were last cleared out, and whether all were (智能建议 off): once each, not every pass. */
    private var clearedOn: LocalDate? = null
    private var clearedAll = false
    private var pending: List<TodayDay>? = null
    private var lastDays: List<TodayDay> = emptyList()
    private var retryJob: Job? = null
    /** Asked the cloud this session (ok or not): never twice for the same text. */
    private val asked = HashSet<String>()
    /** Texts the model could not tag this session: not tried again on every reload (each try loads the model). */
    private val failed = HashSet<String>()

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
            // Tagged — unless mood colours are on and the tag predates mood confidence: redo it, once.
            val have = index.tags(id, n.entry.body)
            if (have != null && (have.moodConf != null || !moodColors)) continue
            // Just written or edited: wait — it is often changed again right away (§19.1).
            val touched = (n.entry.edited ?: n.entry.at)?.toInstant()?.toEpochMilli() ?: 0L
            if (now - touched < SETTLE_MS) { settling = true; continue }
            val key = id + MurmurIndex.hash(n.entry.body)
            if (key in failed) continue
            val t = MurmurLlm.tag(context, n.entry.body) ?: run { failed += key; null } ?: continue
            withContext(Dispatchers.IO) { index.putTags(id, n.entry.body, t) }
            publishFor(days)
        }
        if (smartSuggest) suggest(days)
        else if (!clearedAll && agentJob?.isActive != true) { clearedAll = true; agentJob = scope.launch { clearOldChats(all = true) } }
        if (settling) {
            retryJob?.cancel()
            retryJob = scope.launch { delay(SETTLE_MS); reload() }
        }
    }

    /**
     * §19.3: murmurs from the last day that pass the sift go to Minis, one at a
     * time, in their own job — a background chat can take minutes and must not
     * hold up tagging. Each is judged once (again only if an older brief judged
     * it and the user left it alone). Chats never opened are cleared out the next day.
     */
    private fun suggest(days: List<TodayDay>) {
        clearedAll = false
        if (agentJob?.isActive == true) return
        val since = System.currentTimeMillis() - SUGGEST_WINDOW_MS
        val todo = notesOf(days).filter { it.atMs >= since && !it.entry.writtenByMinis }.sortedByDescending { it.atMs }.filter { n ->
            val id = n.entry.id!!
            val body = n.entry.body
            val tags = index.tags(id, body) ?: return@filter false
            val had = index.suggestion(id, body)
            // Judged before — unless by an older brief and left untouched ("none", or an offer never answered): once more.
            val redo = had != null && had.promptVersion < MurmurAgent.BRIEF_VERSION &&
                (had.state == MurmurSuggestion.NONE || had.state == MurmurSuggestion.OFFER)
            if (had != null && !redo) return@filter false
            MurmurLabels.isCandidate(body, tags) && asked.add(id + MurmurIndex.hash(body))
        }
        val today = LocalDate.now(zone)
        val clear = clearedOn != today
        if (todo.isEmpty() && !clear) return
        agentJob = scope.launch {
            if (clear) { clearOldChats(); clearedOn = today }
            for (n in todo) {
                val id = n.entry.id!!
                // Redone, or the murmur was edited since: the chat from before goes first (any text, so none is left behind).
                // A chat the user opened is theirs now and stays.
                index.rawSuggestion(id)?.takeIf { it.sessionId.isNotBlank() && it.state != MurmurSuggestion.OPENED }?.let { old ->
                    runCatching { MurmurAgent.discard(context, old.sessionId) }
                    withContext(Dispatchers.IO) { index.putSuggestion(id, old.copy(sessionId = "")) }
                }
                _working.value = id
                val s = try {
                    MurmurAgent.prepare(context, n.entry.body, n.atMs, MurmurIndex.hash(n.entry.body))
                } finally {
                    _working.value = null
                }
                if (s != null) withContext(Dispatchers.IO) { index.putSuggestion(id, s) }
                publishFor(lastDays)
            }
        }
    }

    /**
     * Background chats never opened, for offers no longer shown (not today's) —
     * or, [all], every one (智能建议 turned off): deleted.
     */
    private suspend fun clearOldChats(all: Boolean = false) {
        val today = LocalDate.now(zone)
        val cleared = HashMap<String, MurmurSuggestion>()
        for ((id, s) in index.allSuggestions()) {
            if (s.sessionId.isBlank() || s.state == MurmurSuggestion.OPENED) continue
            if (!all && !Instant.ofEpochMilli(s.createdMs).atZone(zone).toLocalDate().isBefore(today)) continue
            runCatching { MurmurAgent.discard(context, s.sessionId) }
            cleared[id] = s.copy(sessionId = "")
        }
        withContext(Dispatchers.IO) { index.putSuggestions(cleared) }
    }

    private fun publishFor(days: List<TodayDay>) {
        val dayMood = if (!moodColors) emptyMap() else days.mapNotNull { d ->
            val tiers = d.rows.filterIsInstance<TodayRow.Note>().mapNotNull { moodOf(it.note.entry)?.let(MoodTier::of) }
            MoodTier.ofDay(tiers)?.let { d.date to it }
        }.toMap()
        val noteMood = if (!moodColors) emptyMap() else notesOf(days).mapNotNull { n -> moodOf(n.entry)?.let { n.entry.id!! to it } }.toMap()
        publish(dayMood, noteMood, visibleSuggestions(days))
    }

    /** The user's own word first (§19.2), else the model's — only when it is sure of it. */
    private fun moodOf(e: JournalEntry): String? = userMood(e) ?: e.id?.let { index.tags(it, e.body)?.shownMood }

    /** `mood=` as written by the user: one of the mood words (older builds wrote 明亮/平常/低沉). */
    private fun userMood(e: JournalEntry): String? = e.attrs[MOOD_ATTR]?.takeIf { it in MurmurLabels.MOODS || MoodTier.fromLabel(it) != null }

    /**
     * What shows under murmurs: today's offers from Minis, open or already
     * opened — at most [MAX_OFFERS_A_DAY], newest kept. Yesterday's fade.
     */
    private fun visibleSuggestions(days: List<TodayDay>): Map<String, MurmurSuggestion> {
        if (!smartSuggest) return emptyMap()
        val today = LocalDate.now(zone)
        val out = HashMap<String, MurmurSuggestion>()
        val offers = ArrayList<Pair<String, MurmurSuggestion>>()
        for (n in notesOf(days)) {
            val id = n.entry.id!!
            val s = index.suggestion(id, n.entry.body) ?: continue
            val live = s.state == MurmurSuggestion.OFFER || s.state == MurmurSuggestion.OPENED
            if (live && s.sessionId.isNotBlank() && Instant.ofEpochMilli(s.createdMs).atZone(zone).toLocalDate() == today) offers += id to s
        }
        offers.sortedByDescending { it.second.createdMs }.take(MAX_OFFERS_A_DAY).forEach { (id, s) -> out[id] = s }
        return out
    }

    // ── What the user does with a suggestion ───────────────────────────────

    /** Apply [change]; returns the suggestion as it was, for undo. */
    private fun update(note: TodayNote, change: (MurmurSuggestion) -> MurmurSuggestion): MurmurSuggestion? {
        val id = note.entry.id ?: return null
        val s = index.suggestion(id, note.entry.body) ?: return null
        index.putSuggestion(id, change(s))
        publishFor(lastDays)
        return s
    }

    /** Tapped: the chat joins the session list; returns it to open. */
    suspend fun open(note: TodayNote): String? {
        val sid = update(note) { it.copy(state = MurmurSuggestion.OPENED) }?.sessionId?.takeIf { it.isNotBlank() } ?: return null
        MurmurAgent.open(context, sid)
        return sid
    }

    /** "不用" (×). Undo puts it back; the chat itself goes with the next day's clear-out. */
    fun dismiss(note: TodayNote): MurmurSuggestion? = update(note) { it.copy(state = MurmurSuggestion.DISMISSED) }

    fun restore(note: TodayNote, previous: MurmurSuggestion) {
        update(note) { previous }
    }

    /** Whether Minis has a model to work with; without one the switch says so (§19.3). */
    fun cloudAvailable(): Boolean = MurmurAgent.hasModel(context)

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
                val tags = index.tags(e.id!!, e.body)
                if (user == null && (tags == null || (moodColors && tags.moodConf == null))) untagged += e
                // Not sure enough of the model's mood: left uncoloured, still settable.
                MoodNote(e.id!!, date, e.at?.toInstant()?.toEpochMilli() ?: 0L, e.body, user ?: tags?.shownMood, user != null)
            }
            days += MoodDay(date, MoodTier.ofDay(notes.mapNotNull { it.tier }), notes)
        }
        lastMonthUntagged = untagged
        return days
    }

    private var lastMonthUntagged: List<JournalEntry> = emptyList()

    /** Older murmurs the timeline never loaded (or tagged before mood confidence): tag them while the page is open. */
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
                out += FoodNote(
                    e.id!!, date, e.at?.toInstant()?.toEpochMilli() ?: date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    e.body, e.photos, names,
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
