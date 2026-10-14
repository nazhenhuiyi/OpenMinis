package com.openminis.app.ui.today

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationManagerCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.journal.CheckIns
import com.openminis.app.data.journal.Habit
import com.openminis.app.data.journal.HabitProgress
import com.openminis.app.data.journal.JournalEntry
import com.openminis.app.data.journal.JournalStore
import com.openminis.app.data.journal.DayWeather
import com.openminis.app.data.journal.TodayLocation
import com.openminis.app.data.journal.TodayWeather
import com.openminis.app.notification.WeatherAlerts
import com.openminis.app.data.photos.CameraPhoto
import com.openminis.app.data.photos.CameraRollRules
import com.openminis.app.data.photos.CameraRollSource
import com.openminis.app.data.photos.MinisCameraCapture
import com.openminis.app.data.photos.PhotoGrouper
import com.openminis.app.data.photos.TimelineItem
import com.openminis.app.data.species.PhotoSpecies
import com.openminis.app.data.species.PhotoTag
import com.openminis.app.data.species.SpeciesId
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** One note in the timeline, with its attached photos resolved (null = not on this phone, SPEC §5.5). */
data class TodayNote(
    val entry: JournalEntry,
    val date: LocalDate,
    val atMs: Long,
    val photos: List<Pair<String, CameraPhoto?>>,
    /** False for duplicated / id-less entries: shown, but not editable (SPEC §4.5). */
    val editable: Boolean,
)

data class TodayGroup(val photos: List<CameraPhoto>, val place: String?)

sealed interface TodayRow {
    val key: String
    data class Note(val note: TodayNote) : TodayRow { override val key get() = "n:" + (note.entry.id ?: note.atMs) }
    data class Group(val group: TodayGroup) : TodayRow { override val key get() = "g:" + group.photos.first().id }
}

data class TodayDay(
    val date: LocalDate,
    val rows: List<TodayRow>,
    /**
     * Read-only check-ins shown at the top of the day (§14.3): a past day's, or
     * today's whose habit is gone from habits.md. Today's live ones are
     * [TodayUiState.todayHabits] — one copy, so the optimistic update touches one list.
     */
    val checkIns: List<HabitProgress> = emptyList(),
    /** Recorded in the day file's front matter (§15); null = none recorded. */
    val weather: DayWeather? = null,
)

/** A photo just taken in the composer, waiting to be sent (SPEC §2.3). */
data class PendingPhoto(val uri: String, val relativePath: String)

data class TodayUiState(
    val loaded: Boolean = false,
    /** Oldest first, as rendered top to bottom. Today is always present. */
    val days: List<TodayDay> = emptyList(),
    val access: CameraRollSource.Access = CameraRollSource.Access.NONE,
    val pending: List<PendingPhoto> = emptyList(),
    val locating: Set<String> = emptySet(),
    val recordLocation: Boolean = true,
    val includedAlbums: Set<String> = emptySet(),
    val hasOlder: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    /** All habits in file order, archived ones included (§14.2). */
    val habits: List<Habit> = emptyList(),
    val showWeather: Boolean = true,
    /** Weather alerts on (§16), and the one that applies right now, pinned under the header (§16.5). */
    val weatherAlerts: Boolean = true,
    val weatherAlert: String? = null,
    /** Today's check-ins (top-bar chip + sheet): habits due today, plus any already checked in today (even if deleted since). */
    val todayHabits: List<HabitProgress> = emptyList(),
    /** Species ID files are on this phone (§17); off = no suggestions and no "identify" item. */
    val speciesReady: Boolean = false,
    /** No usable NPU, but the files are here: the "on the CPU" switch is offered, and whether it is on (§17.1). */
    val speciesCpuOffered: Boolean = false,
    val speciesCpu: Boolean = false,
    /** Species recorded for photos, by relative path (§17.2): shown as a tag on the photo. */
    val tags: Map<String, PhotoTag> = emptyMap(),
    /** Confident species guesses for timeline photos, by relative path (§17.2). */
    val suggestions: Map<String, SpeciesSuggestion> = emptyMap(),
)

/** What Today offers under a photo: "looks like [name]" (§17.2). [genus] = only the genus is claimed. */
data class SpeciesSuggestion(
    val latin: String,
    val name: String,
    val genus: Boolean,
    val group: String?,
    val prob: Float = 1f,
    /** The part of the photo the answer came from, to show beside the reference photos. */
    val region: SpeciesId.Region? = null,
) {
    companion object {
        fun of(g: SpeciesId.Guess, region: SpeciesId.Region? = null) = SpeciesSuggestion(g.latin, g.name, g.genus, g.taxon.group, g.prob, region)
    }
}

/** One time a species was recorded: the entry's moment, words and photos (null = not on this phone). */
data class SpeciesRecord(val atMs: Long, val body: String, val place: String?, val photos: List<CameraPhoto>)

/**
 * One kind in the nature collection (§17.4). [number] is its place in the
 * order things were first recorded — "No.12" was the twelfth kind met.
 */
data class CollectedSpecies(
    val latin: String,
    val name: String,
    val group: String?,
    val genus: String?,
    val number: Int,
    /** Oldest first. */
    val records: List<SpeciesRecord>,
) {
    val first: SpeciesRecord get() = records.first()
    val photos: List<CameraPhoto> get() = records.asReversed().flatMap { it.photos }
}

/**
 * [T-today-tab] State for the Today tab (docs/design/today/SPEC.md). The
 * timeline is rebuilt from scratch on every change: journal files for the
 * notes, the photo library for the photos — groups are never stored (§3).
 */
@OptIn(FlowPreview::class)
class TodayViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()
    private val store = JournalStore.get(app)
    private val roll = CameraRollSource(app)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val _state = MutableStateFlow(
        TodayUiState(
            pending = loadPending(),
            recordLocation = prefs.getBoolean(KEY_RECORD_LOCATION, true),
            includedAlbums = prefs.getStringSet(KEY_ALBUMS, emptySet()).orEmpty(),
            showWeather = prefs.getBoolean(KEY_SHOW_WEATHER, true),
            weatherAlerts = WeatherAlerts.isEnabled(app),
        ),
    )
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    private val _draft = MutableStateFlow(prefs.getString(KEY_DRAFT, "").orEmpty())
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** One-shot UI events (toasts, permission prompts). */
    sealed interface Event {
        data class Toast(val res: Int) : Event
        data object NeedLocationPermission : Event
        data object AllHabitsDone : Event
        data object NeedNotificationPermission : Event
        /**
         * Just recorded a species (§17.2): [times] = how often it has been recorded now, 1 = new to the collection.
         * [photo] is the picture to show it with; [firstOfGroup] = the first bird, first insect… ever.
         */
        data class Collected(
            val latin: String, val name: String, val group: String?, val number: Int, val times: Int, val total: Int, val isNew: Boolean,
            val photo: CameraPhoto? = null, val firstOfGroup: Boolean = false,
        ) : Event
    }
    private val events = Channel<Event>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var windowDays = INITIAL_DAYS
    private val reloadRequests = MutableStateFlow(0)
    private var loadJob: Job? = null

    /** Entry waiting for the location permission answer (SPEC §6.1, §7). */
    private var awaitingLocation: Pair<LocalDate, String>? = null

    /** In-flight camera capture; persisted so a recreated Activity can finish it. */
    var inflightCapture: MinisCameraCapture.Pending? = loadInflight()
        private set

    init {
        viewModelScope.launch {
            merge(store.changes.drop(1), roll.changes(), reloadRequests.drop(1))
                .debounce(RELOAD_DEBOUNCE_MS)
                .collect { load() }
        }
        // A photo deleted or moved: look the collection's photos up again.
        viewModelScope.launch { roll.changes().collect { sightingPhotos.clear(); sightingPhotosGone.clear() } }
        load()
    }

    fun reload() { reloadRequests.value++ }

    /** Opening or returning to Today: reread everything, and refresh today's weather if due (§15.3). */
    fun onResumed() {
        reload()
        refreshWeather()
        WeatherAlerts.schedule(context)
    }

    fun loadOlder() {
        if (!_state.value.hasOlder || loadJob?.isActive == true) return
        windowDays += OLDER_STEP_DAYS
        load()
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val today = LocalDate.now(zone)
            val start = today.minusDays((windowDays - 1).toLong())
            val dates = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
            store.watch(listOf(today, today.minusMonths(1)))
            val access = roll.access()
            val included = _state.value.includedAlbums

            // Notes; check-ins are pulled out into one block per day (§14.3).
            val habits = store.readHabits()
            val notes = ArrayList<TodayNote>()
            val checkInsByDay = HashMap<LocalDate, List<JournalEntry>>()
            val weatherByDay = HashMap<LocalDate, DayWeather>()
            val tags = HashMap<String, PhotoTag>()
            // Files may have changed (here, in Obsidian, by the agent): recount the collection when next needed.
            kinds = null
            for (d in dates) {
                val doc = store.readDay(d) ?: continue
                DayWeather.from(doc.frontMatter)?.let { weatherByDay[d] = it }
                tags += PhotoSpecies.parse(doc.frontMatter)
                val idCounts = doc.entries.groupingBy { it.id }.eachCount()
                val (checkIns, plain) = doc.entries.partition(CheckIns::isCheckIn)
                if (checkIns.isNotEmpty()) checkInsByDay[d] = checkIns
                for (e in plain) {
                    val atMs = e.at?.toInstant()?.toEpochMilli()
                        ?: d.atStartOfDay(zone).toInstant().toEpochMilli()
                    notes += TodayNote(e, d, atMs, emptyList(), editable = e.id != null && idCounts[e.id] == 1)
                }
            }
            // Photos attached to notes, resolved wherever they are in time.
            val attachedPaths = notes.flatMap { it.entry.photos }.toSet()
            val attached = roll.findByRelativePaths(attachedPaths)
            val resolvedNotes = notes.map { n -> n.copy(photos = n.entry.photos.map { it to attached[it] }) }
            // A tag lives in its photo's own day file (§17.2); a note can carry a photo from before the window.
            for (d in attached.values.map { dayOf(it) }.filter { it < start }.toSet()) {
                store.readDay(d)?.let { tags += PhotoSpecies.parse(it.frontMatter) }
            }

            // Free photos in the window, minus attached and pending ones.
            val startMs = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val pendingPaths = _state.value.pending.map { it.relativePath }.toSet()
            val photos = if (access == CameraRollSource.Access.NONE) {
                // Minis-written photos are ours and readable without permission.
                roll.query(startMs, endMs, included).filter { it.relativeDir.startsWith(CameraRollRules.MINIS_DIR) }
            } else {
                roll.query(startMs, endMs, included)
            }.filter { it.relativePath !in attachedPaths && it.relativePath !in pendingPaths }

            val items = PhotoGrouper.build(resolvedNotes, { it.atMs }, photos, zone)
            val showPlaces = _state.value.recordLocation
            val byDay = items.groupBy { java.time.Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() }
            val todayTotals = CheckIns.totals(checkInsByDay[today].orEmpty())
            // Due today, or already checked in today — even if deleted since: today's record still counts.
            val todayHabits = habits.filter { h -> h.dueOn(today) || h.id in todayTotals }
                .map { h -> HabitProgress(h, todayTotals[h.id] ?: 0) }
            val live = todayHabits.mapNotNull { it.habit.id }.toSet()
            val order = habits.mapIndexedNotNull { i, h -> h.id?.let { it to i } }.toMap()
            val byId = habits.filter { it.id != null }.associateBy { it.id!! }
            val days = dates.mapNotNull { d ->
                val rows = byDay[d].orEmpty().map { item ->
                    when (item) {
                        is TimelineItem.Note -> TodayRow.Note(item.note)
                        // Cached place only: never wait on the network before showing photos.
                        is TimelineItem.PhotoGroup -> TodayRow.Group(TodayGroup(item.photos, if (showPlaces) groupPlaces[item.photos.first().id] else null))
                    }
                }
                val entries = checkInsByDay[d].orEmpty().let { es -> if (d == today) es.filter { CheckIns.habitId(it) !in live } else es }
                val block = readOnlyCheckIns(entries, byId, order)
                if (rows.isEmpty() && block.isEmpty() && d != today) null else TodayDay(d, rows, block, weatherByDay[d])
            }
            val hasOlder = store.availableDays().any { it < start } || windowDays < MAX_WINDOW_DAYS
            val namedTags = withListNames(tags)
            _state.update {
                it.copy(
                    loaded = true, days = days, access = access, hasOlder = hasOlder, today = today,
                    habits = habits, todayHabits = todayHabits, tags = namedTags,
                    weatherAlert = WeatherAlerts.active(context, openHabitNames(todayHabits)),
                )
            }
            if (showPlaces) resolvePlaces(days)
            requestSuggestions(days)
        }
    }

    /**
     * Place names for photo groups come from EXIF GPS + reverse geocoding,
     * which can take seconds (or time out) per group. They are filled in
     * after the timeline is on screen, newest day first.
     */
    private suspend fun resolvePlaces(days: List<TodayDay>) {
        val pending = days.asReversed().flatMap { d -> d.rows.filterIsInstance<TodayRow.Group>() }
            .map { it.group.photos }
            .filter { !groupPlaces.containsKey(it.first().id) }
        for (photos in pending) {
            val place = groupPlace(photos) ?: continue
            val key = photos.first().id
            _state.update { st ->
                st.copy(days = st.days.map { d ->
                    d.copy(rows = d.rows.map { r ->
                        if (r is TodayRow.Group && r.group.photos.first().id == key) TodayRow.Group(r.group.copy(place = place)) else r
                    })
                })
            }
        }
    }

    // ── Species (SPEC §17) ────────────────────────────────────────────────

    private val speciesCache = app.getSharedPreferences(SPECIES_PREFS, Context.MODE_PRIVATE)

    private var speciesJob: Job? = null
    private var pendingDays: List<TodayDay>? = null

    /**
     * Species ID runs in its own job, not the timeline load: a pass can take
     * a while (the one-off NPU compile, or ~0.4 s a photo on the CPU), and
     * must neither hold up "load older" nor be cancelled and redone on every
     * reload. A reload meanwhile just queues the newest days for the next pass.
     */
    private fun requestSuggestions(days: List<TodayDay>) {
        pendingDays = days
        if (speciesJob?.isActive == true) return
        speciesJob = viewModelScope.launch {
            while (true) {
                val d = pendingDays ?: break
                pendingDays = null
                suggestSpecies(d)
            }
        }
    }

    /**
     * Run species ID over the photo groups on screen, newest first, a few
     * photos per group; each photo is looked at once and the answer kept
     * (§17.2). Only sure guesses become suggestions; the rest stay quiet.
     */
    private suspend fun suggestSpecies(days: List<TodayDay>) {
        val ready = SpeciesId.isReady(context)
        _state.update { it.copy(speciesReady = ready, speciesCpuOffered = SpeciesId.cpuOffered(context), speciesCpu = SpeciesId.useCpu(context)) }
        if (!ready) return
        migrateSpeciesNotes()
        refreshSpeciesCache()
        // Only the last few days are looked at unasked (§17.2); older photos wait for "Identify".
        // On the CPU (several seconds a photo) only today's photos, so opening Today doesn't keep the CPU busy for minutes.
        val autoDays = if (SpeciesId.backend(context) == SpeciesId.Backend.CPU) 1 else AUTO_DAYS
        val since = LocalDate.now(zone).minusDays((autoDays - 1).toLong())
        val groups = days.filter { !it.date.isBefore(since) }.asReversed()
            .flatMap { d -> d.rows.asReversed().filterIsInstance<TodayRow.Group>() }
        // What was decided before shows at once, in one update; only new photos go to the model.
        val known = HashMap<String, SpeciesSuggestion>()
        val fresh = ArrayList<List<CameraPhoto>>()
        for (g in groups) {
            val todo = ArrayList<CameraPhoto>()
            for (p in g.group.photos) {
                if (p.relativePath in _state.value.tags) continue
                when (val cached = speciesCache.getString(p.relativePath, null)) {
                    null -> todo += p
                    else -> decodeSuggestion(cached)?.let { known[p.relativePath] = it }
                }
            }
            if (todo.isNotEmpty()) fresh += todo
        }
        if (known.isNotEmpty()) {
            val names = SpeciesId.names(context, known.values.map { it.latin }.toSet())
            val named = known.mapValues { (_, c) -> names[c.latin]?.let { c.copy(name = it) } ?: c }
            _state.update { it.copy(suggestions = it.suggestions + named) }
        }
        // A group's new photos are looked at together and appear together: one timeline update per group, not per photo.
        for (todo in fresh) {
            val found = HashMap<String, SpeciesSuggestion>()
            val verdicts = HashMap<String, String>()
            for (p in todo) {
                val r = SpeciesId.identify(context, Uri.parse(p.uri), takenAtMs = p.takenAtMs) ?: continue
                val sure = r.confident?.let { SpeciesSuggestion.of(it, r.region) }
                verdicts[p.relativePath] = sure?.let(::encodeSuggestion) ?: NO_SPECIES
                if (sure != null) found[p.relativePath] = sure
            }
            // A "no" or a tag given meanwhile stands.
            speciesCache.edit().apply { for ((k, v) in verdicts) if (speciesCache.getString(k, null) == null) putString(k, v) }.apply()
            // Only ever a suggestion: adding to the collection is the user's own, deliberate step (§17.2).
            // Not for photos tagged or dismissed while the group was being looked at.
            if (found.isNotEmpty()) _state.update { st ->
                st.copy(suggestions = st.suggestions + found.filterKeys { it !in st.tags && speciesCache.getString(it, null) != USER_NO })
            }
        }
        // Ready for the next Record tap, off the load path (rebuilt only after a reload dropped it).
        kinds()
        collection()
    }

    /** Candidates for one photo (§17.3), remembered for this screen so flipping between photos is instant. */
    private val identified = HashMap<String, SpeciesId.Result>()

    suspend fun identify(photo: CameraPhoto): SpeciesId.Result? =
        identified[photo.relativePath] ?: SpeciesId.identify(context, Uri.parse(photo.uri), k = 3, takenAtMs = photo.takenAtMs)?.also { identified[photo.relativePath] = it }

    /** Only the part of [photo] the user drew a box around (§17.3); not remembered. */
    suspend fun identify(photo: CameraPhoto, region: SpeciesId.Region): SpeciesId.Result? =
        SpeciesId.identify(context, Uri.parse(photo.uri), k = 3, region = region, takenAtMs = photo.takenAtMs)

    /** Where "Identify" opens on [photos] (§17.3): one already suggested, else the first — shown at once, never after a search. */
    fun startPhoto(photos: List<CameraPhoto>): CameraPhoto? =
        photos.firstOrNull { it.relativePath in _state.value.suggestions } ?: photos.firstOrNull()

    /**
     * Record [s] for [photos] (§17.2, §17.3): a tag on each photo, written to
     * the front matter of the photo's day. The photos stay where they are —
     * in their group or under their note.
     */
    fun record(photos: List<CameraPhoto>, s: SpeciesSuggestion) {
        if (photos.isEmpty()) return
        photos.forEach { clearSuggestion(it.relativePath) }
        // The plain name only; "a kind of X" for a genus is added when shown, in the language of the moment.
        val tag = PhotoTag(s.latin, s.name, s.group)
        val replaced = photos.mapNotNull { _state.value.tags[it.relativePath] }.filter { it.latin != tag.latin }
        val fresh = photos.filter { _state.value.tags[it.relativePath]?.latin != tag.latin }
        _state.update { st -> st.copy(tags = st.tags + photos.associate { it.relativePath to tag }) }
        viewModelScope.launch {
            // Celebrate on the tap, not after the file write and a rescan (§17.2).
            if (fresh.isNotEmpty()) events.send(count(tag, added = fresh.size, replacing = replaced).copy(photo = fresh.first()))
            writeTags(photos.associateWith { tag })
        }
    }

    /** Take a photo's tag off (wrong guess). It is not suggested or tagged again. Returns how to undo it. */
    fun untag(photo: CameraPhoto): (() -> Unit)? {
        val path = photo.relativePath
        val old = _state.value.tags[path] ?: return null
        val verdict = speciesCache.getString(path, null)
        _state.update { it.copy(tags = it.tags - path) }
        speciesCache.edit().putString(path, USER_NO).apply()
        kinds?.let { m -> m[old.latin]?.let { n -> if (n <= 1) m.remove(old.latin) else m[old.latin] = n - 1 } }
        viewModelScope.launch { writeTags(mapOf(photo to null)) }
        return {
            _state.update { it.copy(tags = it.tags + (path to old)) }
            speciesCache.edit().apply { if (verdict == null) remove(path) else putString(path, verdict) }.apply()
            kinds?.let { m -> m[old.latin] = (m[old.latin] ?: 0) + 1 }
            viewModelScope.launch { writeTags(mapOf(photo to old)) }
        }
    }

    /** Tag writes are read-modify-write of one front-matter line: one at a time, or a quick second Record loses the first. */
    private val tagLock = kotlinx.coroutines.sync.Mutex()

    private fun dayOf(photo: CameraPhoto): LocalDate = java.time.Instant.ofEpochMilli(photo.takenAtMs).atZone(zone).toLocalDate()

    /** Write (null = remove) tags, one front-matter update per day file. */
    private suspend fun writeTags(changes: Map<CameraPhoto, PhotoTag?>) {
        for ((day, cs) in changes.entries.groupBy { dayOf(it.key) }) setTags(day, cs.associate { it.key.relativePath to it.value })
    }

    private suspend fun setTag(day: LocalDate, path: String, tag: PhotoTag?) = setTags(day, mapOf(path to tag))

    private suspend fun setTags(day: LocalDate, changes: Map<String, PhotoTag?>) = tagLock.withLock {
        val tags = LinkedHashMap(PhotoSpecies.parse(store.readDay(day)?.frontMatter.orEmpty()))
        for ((path, tag) in changes) if (tag == null) tags.remove(path) else tags[path] = tag
        store.setFrontMatter(day, mapOf(PhotoSpecies.KEY to PhotoSpecies.render(tags)))
    }

    /**
     * The first preview recorded a species as a new note ("🐦 白头鹎" with the
     * photo under it), which pulled the photo out of its group. Notes that are
     * exactly that — one photo, words that are only the emoji and the name —
     * become tags on their photo again. Anything the user wrote stays a note.
     */
    private suspend fun migrateSpeciesNotes() {
        if (prefs.getBoolean(KEY_SPECIES_MIGRATED, false)) return
        val legacy = store.availableDays().flatMap { d -> store.readDay(d)?.entries.orEmpty().filter { it.species != null }.flatMap { it.photos } }
        val photoDays = roll.findByRelativePaths(legacy.toSet()).mapValues { dayOf(it.value) }
        for (d in store.availableDays()) {
            val doc = store.readDay(d) ?: continue
            for (e in doc.entries) {
                val latin = e.species ?: continue
                val name = e.attrs[JournalEntry.KEY_SPECIES_NAME] ?: continue
                val path = e.photos.singleOrNull() ?: continue
                if (e.body.trim().substringAfter(' ') != name || e.body.trim().length > name.length + 4) continue
                val group = SpeciesId.taxon(context, latin)?.group
                // Into the photo's own day file, where every later read and untag looks (§17.2).
                setTag(photoDays[path] ?: d, path, PhotoTag(latin, name, group))
                e.id?.let { store.delete(d, it) }
            }
        }
        prefs.edit().putBoolean(KEY_SPECIES_MIGRATED, true).apply()
    }

    /**
     * The model's verdicts are kept per photo; when how photos are looked at
     * or the species list changes ([SpeciesId.verdictVersion]), they are dropped
     * so recent photos are looked at again. What the user said no to stays.
     */
    private fun refreshSpeciesCache() {
        val version = SpeciesId.verdictVersion(context)
        if (prefs.getString(KEY_SPECIES_VERDICTS, null) == version) return
        val e = speciesCache.edit()
        for ((k, v) in speciesCache.all) if (v != USER_NO) e.remove(k)
        e.apply()
        prefs.edit().putString(KEY_SPECIES_VERDICTS, version).apply()
    }

    /** Tags whose stored name has no Chinese (written before the list had one) read with today's list's name. */
    /** Tags named by today's list (their stored names are only the fallback). */
    private suspend fun withListNames(tags: Map<String, PhotoTag>): Map<String, PhotoTag> {
        val names = SpeciesId.names(context, tags.values.map { it.latin }.toSet())
        return tags.mapValues { (_, t) -> names[t.latin]?.let { t.copy(name = it) } ?: t }
    }

    /**
     * One sighting found in the files: a tagged photo ([tag]), or (older data) a
     * note carrying `species` ([entryId]). [day] is the file it lives in.
     */
    private class Sighting(
        val latin: String, val name: String, val atMs: Long, val body: String, val place: String?, val photos: List<String>,
        val day: LocalDate? = null, val tag: PhotoTag? = null, val entryId: String? = null,
    )

    /**
     * The sightings of each day file, kept with the file's size and time so a
     * day is parsed again only when it changed — whoever changed it (Today,
     * Obsidian, the agent). Opening the collection then reads no files at all
     * on most days, however long the journal grows.
     */
    private class DaySightings(val stamp: Pair<Long, Long>, val list: List<Sighting>)
    private val sightingIndex = HashMap<LocalDate, DaySightings>()
    private val sightingLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun sightings(): List<Sighting> = sightingLock.withLock {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val days = store.availableDays()
            sightingIndex.keys.retainAll(days.toSet())
            val out = ArrayList<Sighting>()
            for (d in days) {
                val f = store.fileFor(d)
                val stamp = f.length() to f.lastModified()
                val kept = sightingIndex[d]?.takeIf { it.stamp == stamp }
                    ?: DaySightings(stamp, readSightings(d)).also { sightingIndex[d] = it }
                out += kept.list
            }
            out
        }
    }

    private suspend fun readSightings(d: LocalDate): List<Sighting> {
        val doc = store.readDay(d) ?: return emptyList()
        val dayMs = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val out = ArrayList<Sighting>()
        for ((path, t) in PhotoSpecies.parse(doc.frontMatter)) out += Sighting(t.latin, t.name, dayMs, "", null, listOf(path), d, tag = t)
        for (e in doc.entries) {
            val latin = e.species ?: continue
            val atMs = e.at?.toInstant()?.toEpochMilli() ?: dayMs
            out += Sighting(latin, e.attrs[JournalEntry.KEY_SPECIES_NAME] ?: latin, atMs, e.body, e.place, e.photos, d, entryId = e.id)
        }
        return out
    }

    /** Photos of sightings (and those no longer on the phone), looked up once; forgotten when the photo library changes. */
    private val sightingPhotos = java.util.concurrent.ConcurrentHashMap<String, CameraPhoto>()
    private val sightingPhotosGone: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private suspend fun photosOf(paths: Set<String>): Map<String, CameraPhoto> {
        val unknown = paths.filter { !sightingPhotos.containsKey(it) && it !in sightingPhotosGone }.toSet()
        if (unknown.isNotEmpty()) {
            val found = roll.findByRelativePaths(unknown)
            sightingPhotos += found
            sightingPhotosGone += unknown - found.keys
        }
        return paths.mapNotNull { p -> sightingPhotos[p]?.let { p to it } }.toMap()
    }

    /**
     * Recorded kinds in the order first met, with how often each was recorded:
     * enough to say "No. 12" or "seen again, 3 times" the instant Record is
     * tapped. Built once from the files, then kept up to date in memory.
     */
    private var kinds: LinkedHashMap<String, Int>? = null

    private suspend fun kinds(): LinkedHashMap<String, Int> = kinds ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val m = LinkedHashMap<String, Int>()
        timedSightings().first.sortedBy { it.atMs }.forEach { m[it.latin] = (m[it.latin] ?: 0) + 1 }
        m
    }.also { kinds = it }

    /**
     * Every sighting at its real moment — a tagged photo at the time it was
     * taken — so "No. 12" means the same in the banner and the collection.
     * Also returns the photos, for the collection's pictures.
     */
    private suspend fun timedSightings(): Pair<List<Sighting>, Map<String, CameraPhoto>> {
        val raw = sightings()
        val photos = photosOf(raw.flatMap { it.photos }.toSet())
        val timed = raw.map { r ->
            if (r.body.isNotEmpty()) r
            else r.photos.firstNotNullOfOrNull { photos[it] }?.let { p -> Sighting(r.latin, r.name, p.takenAtMs, r.body, r.place, r.photos, r.day, r.tag, r.entryId) } ?: r
        }
        return timed to photos
    }

    /** Moves the collection counts for a recording, and says what it means: new kind or seen again, and its number. */
    private suspend fun count(t: PhotoTag, added: Int, replacing: List<PhotoTag>): Event.Collected {
        val m = kinds()
        for (old in replacing) m[old.latin]?.let { n -> if (n <= 1) m.remove(old.latin) else m[old.latin] = n - 1 }
        val isNew = m[t.latin] == null
        val firstOfGroup = isNew && t.group != null && m.keys.none { groupOf(it) == t.group }
        m[t.latin] = (m[t.latin] ?: 0) + added
        return Event.Collected(t.latin, t.name, t.group, m.keys.indexOf(t.latin) + 1, m.getValue(t.latin), m.size, isNew, firstOfGroup = firstOfGroup)
    }

    /** Bird, plant, insect…: from the species list, or for a genus from one of its species. */
    private suspend fun groupOf(latin: String): String? =
        SpeciesId.taxon(context, latin)?.group ?: if (' ' !in latin) SpeciesId.anyOfGenus(context, latin)?.group else null

    /** "Not this": the suggestions on [photos] go away for good. Returns how to undo it. */
    fun dismissSuggestions(photos: List<CameraPhoto>): () -> Unit {
        val old = photos.mapNotNull { p -> _state.value.suggestions[p.relativePath]?.let { p.relativePath to it } }.toMap()
        photos.forEach { clearSuggestion(it.relativePath, userSaidNo = true) }
        return {
            speciesCache.edit().apply { old.forEach { (path, s) -> putString(path, encodeSuggestion(s)) } }.apply()
            _state.update { it.copy(suggestions = it.suggestions + old) }
        }
    }

    private fun clearSuggestion(path: String, userSaidNo: Boolean = false) {
        speciesCache.edit().putString(path, if (userSaidNo) USER_NO else NO_SPECIES).apply()
        _state.update { it.copy(suggestions = it.suggestions - path) }
    }

    /**
     * Take things out of the collection (§17.4): every kind in [latins] — or,
     * with [photos], only those photos of them. Photo tags come off (and those
     * photos are not suggested again); a note that carries the species keeps
     * its words and loses only the species. No photo or note is deleted.
     * Returns how to undo it.
     */
    suspend fun removeFromCollection(latins: Set<String>, photos: Set<String>? = null): suspend () -> Unit {
        val hit = sightings().filter { it.latin in latins && (photos == null || it.photos.any { p -> p in photos }) }
        val tagged = hit.filter { it.tag != null && it.day != null }
        val notes = hit.filter { it.entryId != null && it.day != null }
        val paths = tagged.flatMap { it.photos }
        val verdicts = paths.associateWith { speciesCache.getString(it, null) }
        speciesCache.edit().apply { paths.forEach { putString(it, USER_NO) } }.apply()
        _state.update { st -> st.copy(tags = st.tags - paths.toSet(), suggestions = st.suggestions - paths.toSet()) }
        for ((day, ss) in tagged.groupBy { it.day!! }) setTags(day, ss.associate { it.photos.single() to null })
        val noteAttrs = notes.associateWith { n ->
            store.readDay(n.day!!)?.entries?.firstOrNull { it.id == n.entryId }?.attrs.orEmpty()
                .filterKeys { it == JournalEntry.KEY_SPECIES || it == JournalEntry.KEY_SPECIES_NAME }
        }
        for (n in notes) store.setAttrs(n.day!!, n.entryId!!, mapOf(JournalEntry.KEY_SPECIES to null, JournalEntry.KEY_SPECIES_NAME to null))
        kinds = null
        lastCollection = null
        return {
            speciesCache.edit().apply { verdicts.forEach { (p, v) -> if (v == null) remove(p) else putString(p, v) } }.apply()
            for ((day, ss) in tagged.groupBy { it.day!! }) setTags(day, ss.associate { it.photos.single() to it.tag })
            for ((n, attrs) in noteAttrs) store.setAttrs(n.day!!, n.entryId!!, attrs)
            _state.update { st -> st.copy(tags = st.tags + withListNames(tagged.associate { it.photos.single() to it.tag!! })) }
            kinds = null
            lastCollection = null
        }
    }

    /** The collection as last built, to show at once while it is brought up to date. */
    @Volatile
    var lastCollection: List<CollectedSpecies>? = null
        private set

    /** Everything recorded, across all days, most recently seen first (§17.4). */
    suspend fun collection(): List<CollectedSpecies> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val (all, photos) = timedSightings()
        val names = SpeciesId.names(context, all.map { it.latin }.toSet())
        all.groupBy { it.latin }.values
            .map { rs -> rs.sortedBy { it.atMs } }
            .sortedBy { it.first().atMs }
            .mapIndexed { i, rs ->
                val t = SpeciesId.taxon(context, rs.first().latin)
                CollectedSpecies(
                    latin = rs.first().latin,
                    name = names[rs.first().latin] ?: rs.last().name,
                    group = t?.group,
                    genus = t?.let { it.genusZh ?: it.genusLatin },
                    number = i + 1,
                    records = rs.map { r -> SpeciesRecord(r.atMs, r.body, r.place, r.photos.mapNotNull { photos[it] }) },
                )
            }
            .sortedByDescending { it.records.last().atMs }
            .also { lastCollection = it }
    }

    // ── Species page (§17.4) ──────────────────────────────────────────────

    /** Names, how common, ranks above and look-alikes — all from the species list, offline. */
    suspend fun speciesProfile(latin: String): SpeciesId.Profile? = SpeciesId.profile(context, latin)

    /** Wikipedia summary and month-by-month sightings; the cached copy first, then a refresh if due. */
    suspend fun speciesInfoCached(latin: String): com.openminis.app.data.species.SpeciesInfo.Info? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.openminis.app.data.species.SpeciesInfo.cached(context, latin) }

    suspend fun speciesInfo(t: SpeciesId.Taxon): com.openminis.app.data.species.SpeciesInfo.Info? =
        com.openminis.app.data.species.SpeciesInfo.get(context, t)

    /** What a kind looks like (openly licensed iNaturalist photos), for the candidates in "Identify" (§17.3). */
    suspend fun refPhotos(latin: String, inatId: Long?): List<com.openminis.app.data.species.SpeciesInfo.RefPhoto> =
        com.openminis.app.data.species.SpeciesInfo.refPhotos(context, latin, inatId)

    /**
     * What the "What is it?" page shows for a suggestion (§17.3): the species
     * from the list — or, for a genus, a stand-in built from one of its
     * species, so names, group and the Wikipedia lookup still work.
     */
    suspend fun describe(s: SpeciesSuggestion): SpeciesId.Taxon {
        if (!s.genus) SpeciesId.taxon(context, s.latin)?.let { return it }
        val any = SpeciesId.anyOfGenus(context, s.latin)
        return SpeciesId.Taxon(
            latin = s.latin, zh = s.name.takeIf { !s.genus }, genusLatin = any?.genusLatin ?: s.latin.substringBefore(' '),
            genusZh = any?.genusZh ?: s.name.takeIf { s.genus }, group = s.group ?: any?.group, chain = any?.chain.orEmpty(),
        )
    }

    /** Where a photo was taken, from its GPS (§6.2); null without location data. */
    suspend fun placeOf(photo: CameraPhoto): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ll = readLatLng(Uri.parse(photo.uri)) ?: return@withContext null
        TodayLocation.placeName(context, ll.first, ll.second)
    }

    /** Other kinds in the collection from the same family as [latin] (§17.4). */
    suspend fun sameFamily(latin: String, all: List<CollectedSpecies>): List<CollectedSpecies> {
        val taxa = SpeciesId.taxa(context, all.map { it.latin } + latin)
        val family = taxa[latin]?.chain?.lastOrNull()?.first ?: return emptyList()
        return all.filter { it.latin != latin && taxa[it.latin]?.chain?.lastOrNull()?.first == family }
    }

    /** Warm the encoder while the user looks at the timeline, so the one-off NPU compile is out of the way. */
    fun warmUpSpecies() = SpeciesId.warmUp(context)

    private fun encodeSuggestion(s: SpeciesSuggestion) = listOf(
        s.latin, s.name, if (s.genus) "1" else "0", s.group.orEmpty(), s.prob.toString(),
        s.region?.let { r -> listOf(r.left, r.top, r.right, r.bottom).joinToString(",") }.orEmpty(),
    ).joinToString("\t")

    private fun decodeSuggestion(v: String): SpeciesSuggestion? {
        if (v == NO_SPECIES || v == USER_NO) return null
        val f = v.split('\t')
        val region = f.getOrNull(5)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 }?.let { SpeciesId.Region(it[0], it[1], it[2], it[3]) }
        return if (f.size < 3) null else SpeciesSuggestion(f[0], f[1], f[2] == "1", f.getOrNull(3)?.ifBlank { null }, f.getOrNull(4)?.toFloatOrNull() ?: 1f, region)
    }

    // ── Composer ──────────────────────────────────────────────────────────

    fun setDraft(text: String) {
        _draft.value = text
        prefs.edit().putString(KEY_DRAFT, text).apply()
    }

    fun send() {
        val text = _draft.value.trim()
        val pending = _state.value.pending
        if (text.isEmpty() && pending.isEmpty()) return
        setDraft("")
        setPending(emptyList())
        if (text.isEmpty()) {
            // No words: the photos are just library photos now (SPEC §2.3).
            events.trySend(Event.Toast(com.openminis.app.R.string.today_photos_returned))
            reload()
            return
        }
        viewModelScope.launch {
            val entry = store.add(text, pending.map { it.relativePath })
            val date = entry.at!!.toLocalDate()
            if (_state.value.recordLocation) {
                if (TodayLocation.hasPermission(context)) locate(date, entry.id!!)
                else if (askLocationOnce()) awaitingLocation = date to entry.id!!
            }
        }
    }

    /**
     * Notes (§6.1) and weather (§15.3) share one location prompt, asked once
     * ever: the flag is written when the request goes out, so nothing can
     * stack a second prompt while the first is on screen. False = already asked.
     */
    private fun askLocationOnce(): Boolean {
        if (prefs.getBoolean(KEY_LOCATION_ASKED, false)) return false
        prefs.edit().putBoolean(KEY_LOCATION_ASKED, true).apply()
        events.trySend(Event.NeedLocationPermission)
        return true
    }

    /** Result of the shared location prompt; each asker gets its answer. */
    fun onLocationPermissionResult(granted: Boolean) {
        val note = awaitingLocation.also { awaitingLocation = null }
        val weather = awaitingWeather.also { awaitingWeather = false }
        if (granted) {
            note?.let { (d, id) -> locate(d, id) }
            // refreshWeather asks for notifications itself once it has a position.
            if (weather) refreshWeather(force = true) else askNotificationsOnce()
            return
        }
        if (note != null) {
            setRecordLocation(false)
            events.trySend(Event.Toast(com.openminis.app.R.string.today_location_off_toast))
        }
        if (weather) {
            setShowWeather(false)
            events.trySend(Event.Toast(com.openminis.app.R.string.today_weather_off_toast))
        }
    }

    /** SPEC §6.1: the entry is already on screen; coordinates and place are backfilled. */
    private fun locate(date: LocalDate, id: String) {
        _state.update { it.copy(locating = it.locating + id) }
        viewModelScope.launch {
            try {
                val fix = TodayLocation.fix(context) ?: return@launch
                store.setAttrs(date, id, TodayLocation.attrsFor(fix))
                TodayLocation.placeName(context, fix.lat, fix.lng)?.let {
                    store.setAttrs(date, id, mapOf(JournalEntry.KEY_PLACE to it))
                }
            } finally {
                _state.update { it.copy(locating = it.locating - id) }
            }
        }
    }

    // ── Camera (SPEC §5.3) ────────────────────────────────────────────────

    fun prepareCapture(): MinisCameraCapture.Pending? =
        MinisCameraCapture.prepare(context)?.also { setInflight(it) }

    fun finishCapture() {
        val p = inflightCapture ?: return
        setInflight(null)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val uri = MinisCameraCapture.finish(context, p) ?: return@launch
            setPending(_state.value.pending + PendingPhoto(uri.toString(), p.relativePath))
            events.send(Event.Toast(com.openminis.app.R.string.today_photo_saved))
        }
    }

    /** × on a composer thumbnail: it was just taken, so it leaves the library too (SPEC §2.3). */
    fun discardPending(photo: PendingPhoto) {
        setPending(_state.value.pending - photo)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            MinisCameraCapture.discard(context, Uri.parse(photo.uri))
            events.send(Event.Toast(com.openminis.app.R.string.today_photo_discarded))
        }
    }

    // ── Entry actions (SPEC §2.4) ─────────────────────────────────────────

    fun edit(note: TodayNote, body: String) {
        val id = note.entry.id ?: return
        if (body.isBlank() || body.trim() == note.entry.body) return
        viewModelScope.launch { store.editBody(note.date, id, body) }
    }

    fun delete(note: TodayNote) {
        val id = note.entry.id ?: return
        viewModelScope.launch { store.delete(note.date, id) }
    }

    // ── Weather (SPEC §15) ───────────────────────────────────────────────

    private var weatherJob: Job? = null
    private var lastWeatherAttemptMs = 0L
    private var awaitingWeather = false

    /**
     * Today's weather goes into today's front matter (§15.3), on open/resume
     * only — not on every reload. Fetched when missing or older than
     * [TodayWeather.REFRESH_MS]; past days are never refetched; a failure is
     * retried at most every [WEATHER_RETRY_MS] unless [force]d.
     */
    private fun refreshWeather(force: Boolean = false) {
        if (!_state.value.showWeather || weatherJob?.isActive == true) return
        if (!TodayLocation.hasPermission(context)) {
            if (askLocationOnce()) awaitingWeather = true
            return
        }
        askNotificationsOnce()
        val nowMs = store.now().toInstant().toEpochMilli()
        if (!force && nowMs - lastWeatherAttemptMs < WEATHER_RETRY_MS) return
        weatherJob = viewModelScope.launch {
            val today = store.now().toLocalDate()
            val updated = store.readDay(today)?.let { DayWeather.from(it.frontMatter) }?.updated
            if (updated != null && nowMs - updated.toInstant().toEpochMilli() < TodayWeather.REFRESH_MS) return@launch
            lastWeatherAttemptMs = nowMs
            val fix = TodayLocation.coarse(context) ?: return@launch
            // The hourly alert check reuses this position; it never asks for background location (§16.3).
            WeatherAlerts.saveLocation(context, fix.lat, fix.lng)
            val w = TodayWeather.fetch(fix.lat, fix.lng, store.now()) ?: return@launch
            store.setFrontMatter(today, w.frontMatter(context.getString(TodayWeather.label(w.code))))
        }
    }

    private fun openHabitNames(habits: List<HabitProgress>) = habits.filterNot { it.complete }.map { it.habit.name }

    fun setWeatherAlerts(on: Boolean) {
        WeatherAlerts.setEnabled(context, on)
        _state.update { it.copy(weatherAlerts = on, weatherAlert = WeatherAlerts.active(context, openHabitNames(it.todayHabits))) }
        if (on) askNotificationsOnce(force = true)
    }

    fun dismissWeatherAlert() {
        WeatherAlerts.dismiss(context)
        _state.update { it.copy(weatherAlert = null) }
    }

    /**
     * Alerts are on by default but need POST_NOTIFICATIONS on Android 13+.
     * Asked once, after the location answer so two prompts never stack (§16.6).
     */
    private fun askNotificationsOnce(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < 33 || !WeatherAlerts.isEnabled(context)) return
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (!force && prefs.getBoolean(KEY_NOTIFY_ASKED, false)) return
        prefs.edit().putBoolean(KEY_NOTIFY_ASKED, true).apply()
        events.trySend(Event.NeedNotificationPermission)
    }

    fun setShowWeather(on: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_WEATHER, on).apply()
        _state.update { it.copy(showWeather = on) }
        if (!on) return
        if (TodayLocation.hasPermission(context)) refreshWeather(force = true)
        else {
            // Turned on by hand: ask even if asked before (the system may still refuse silently).
            awaitingWeather = true
            events.trySend(Event.NeedLocationPermission)
        }
    }

    // ── Daily check-ins (SPEC §14) ────────────────────────────────────────

    /** Tap on an open habit: fill it to its goal. Done ones go to the adjust panel instead (§14.3). */
    /** One tap in the sheet: add [by] to today's total (§14.3). */
    fun stepHabit(habitId: String, by: Int) {
        val p = _state.value.todayHabits.firstOrNull { it.habit.id == habitId } ?: return
        if (!p.complete) setHabitDone(p.habit, p.done + by)
    }

    /** Set today's total (adjust panel), 0 = undo. Always today — no backfilling (§14.1). */
    fun setHabitDone(habit: Habit, total: Int) {
        // Optimistic: the ring moves on the tap, not after the file round-trip;
        // the reload that follows the write confirms it.
        val n = total.coerceAtLeast(0)
        val before = _state.value.todayHabits
        val after = before.map { p -> if (p.habit.id == habit.id) p.copy(done = n) else p }
        _state.update { it.copy(todayHabits = after) }
        if (!allDone(before) && allDone(after)) events.trySend(Event.AllHabitsDone)
        viewModelScope.launch { store.setCheckIn(store.now().toLocalDate(), habit, n) }
    }

    private fun allDone(list: List<HabitProgress>) = list.isNotEmpty() && list.all { it.complete }

    fun addHabit(name: String, target: Int?, unit: String?, days: Set<java.time.DayOfWeek>?) {
        viewModelScope.launch { store.addHabit(name, target, unit, days) }
    }

    fun updateHabit(habit: Habit, name: String, target: Int?, unit: String?, days: Set<java.time.DayOfWeek>?) {
        val id = habit.id ?: return
        viewModelScope.launch { store.updateHabit(id) { it.withDefinition(name, target, unit, days) } }
    }

    /** Delete = archive; the line stays so past check-ins keep their name (§14.2). */
    fun archiveHabit(habit: Habit) {
        val id = habit.id ?: return
        viewModelScope.launch {
            store.updateHabit(id) { it.withAttrs(mapOf(Habit.KEY_ARCHIVED to store.now().toLocalDate().toString())) }
        }
    }

    /** A day's check-ins, one per habit with the day's total, in habits.md order; read-only. */
    private fun readOnlyCheckIns(entries: List<JournalEntry>, byId: Map<String, Habit>, order: Map<String, Int>): List<HabitProgress> =
        entries.groupBy { CheckIns.habitId(it)!! }
            .entries.sortedBy { order[it.key] ?: Int.MAX_VALUE }
            .map { (id, es) ->
                // Unknown id (habits.md edited by hand): a stand-in named from the entry's own words.
                val h = byId[id] ?: Habit(es.first().body.removePrefix("✅ ").substringBefore(' '), linkedMapOf(Habit.KEY_ID to id))
                HabitProgress(h, es.sumOf(CheckIns::done), editable = false)
            }

    // ── Settings (⋯ menu) ─────────────────────────────────────────────────

    /** Species ID on the CPU, for phones whose NPU can't run it (§17.1). */
    fun setSpeciesCpu(on: Boolean) {
        SpeciesId.setUseCpu(context, on)
        _state.update { it.copy(speciesCpu = on) }
        reload()
    }

    fun setRecordLocation(on: Boolean) {
        prefs.edit().putBoolean(KEY_RECORD_LOCATION, on).apply()
        _state.update { it.copy(recordLocation = on) }
        reload()
    }

    fun setIncludedAlbums(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_ALBUMS, ids).apply()
        _state.update { it.copy(includedAlbums = ids) }
        reload()
    }

    suspend fun albums(): List<CameraRollSource.Album> = roll.albums()

    // ── Photo place names from EXIF (SPEC §6.2) ───────────────────────────

    private val groupPlaces = HashMap<Long, String?>()

    private suspend fun groupPlace(photos: List<CameraPhoto>): String? {
        val key = photos.first().id
        if (groupPlaces.containsKey(key)) return groupPlaces[key]
        val place = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            for (p in photos.take(MAX_EXIF_READS)) {
                val ll = readLatLng(Uri.parse(p.uri)) ?: continue
                return@withContext TodayLocation.placeName(context, ll.first, ll.second)
            }
            null
        }
        groupPlaces[key] = place
        return place
    }

    /** Android 10+ redacts GPS unless we hold ACCESS_MEDIA_LOCATION and ask for the original. */
    private fun readLatLng(uri: Uri): Pair<Double, Double>? = runCatching {
        val source = if (Build.VERSION.SDK_INT >= 29) MediaStore.setRequireOriginal(uri) else uri
        context.contentResolver.openInputStream(source)?.use { input ->
            ExifInterface(input).latLong?.let { it[0] to it[1] }
        }
    }.getOrNull()

    // ── Persistence ───────────────────────────────────────────────────────

    private fun setPending(list: List<PendingPhoto>) {
        _state.update { it.copy(pending = list) }
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("uri", it.uri).put("path", it.relativePath)) }
        prefs.edit().putString(KEY_PENDING, arr.toString()).apply()
    }

    private fun loadPending(): List<PendingPhoto> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_PENDING, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> PendingPhoto(o.getString("uri"), o.getString("path")) } }
    }.getOrDefault(emptyList())

    private fun setInflight(p: MinisCameraCapture.Pending?) {
        inflightCapture = p
        val e = prefs.edit()
        if (p == null) e.remove(KEY_INFLIGHT) else e.putString(
            KEY_INFLIGHT,
            JSONObject().put("uri", p.captureUri.toString()).put("path", p.relativePath).put("staging", p.stagingFile).toString(),
        )
        e.apply()
    }

    private fun loadInflight(): MinisCameraCapture.Pending? = runCatching {
        val o = JSONObject(prefs.getString(KEY_INFLIGHT, null) ?: return null)
        MinisCameraCapture.Pending(Uri.parse(o.getString("uri")), o.getString("staging"), o.getString("path"))
    }.onFailure { AppLogger.warning(TAG, "bad inflight capture: ${it.message}") }.getOrNull()

    override fun onCleared() {
        store.stopWatching()
        super.onCleared()
    }

    companion object {
        private const val TAG = "TodayVM"
        const val PREFS = "today_prefs"

        /** "Record location" in the Today ⋯ menu (§6.4); the agent's notes follow it too. */
        fun recordsLocation(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RECORD_LOCATION, true)
        private const val KEY_DRAFT = "draft"
        private const val KEY_PENDING = "pending"
        private const val KEY_INFLIGHT = "inflight"
        private const val KEY_RECORD_LOCATION = "recordLocation"
        private const val KEY_LOCATION_ASKED = "locationAsked"
        private const val KEY_ALBUMS = "includedAlbums"
        private const val KEY_SHOW_WEATHER = "showWeather"
        private const val KEY_NOTIFY_ASKED = "notifyAsked"
        private const val WEATHER_RETRY_MS = 10 * 60 * 1000L
        private const val INITIAL_DAYS = 7
        private const val OLDER_STEP_DAYS = 7
        private const val MAX_WINDOW_DAYS = 90
        private const val RELOAD_DEBOUNCE_MS = 500L
        private const val MAX_EXIF_READS = 5
        private const val SPECIES_PREFS = "today_species"
        private const val KEY_SPECIES_MIGRATED = "speciesNotesMigrated"
        private const val NO_SPECIES = "-"
        /** The user dismissed a suggestion or took a tag off: never suggested again, kept across cache refreshes. */
        private const val USER_NO = "x"
        private const val KEY_SPECIES_VERDICTS = "speciesVerdictsMadeWith"
        /** Photos of a group "Identify" compares to choose where to open. */
        /** Days (today included) the automatic pass looks at (§17.2). */
        private const val AUTO_DAYS = 3

        /** A little sign for the kind of life in an entry (§17.2). */
        fun speciesEmoji(group: String?): String = when (group) {
            "Plantae" -> "🌿"
            "Aves" -> "🐦"
            "Insecta" -> "🦋"
            "Mammalia" -> "🐾"
            "Fungi" -> "🍄"
            "Reptilia" -> "🦎"
            "Amphibia" -> "🐸"
            "Actinopterygii" -> "🐟"
            "Arachnida" -> "🕷️"
            "Mollusca" -> "🐌"
            else -> "✦"
        }
    }
}
