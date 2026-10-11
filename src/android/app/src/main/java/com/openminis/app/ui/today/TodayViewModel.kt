package com.openminis.app.ui.today

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.journal.JournalEntry
import com.openminis.app.data.journal.JournalStore
import com.openminis.app.data.journal.TodayLocation
import com.openminis.app.data.photos.CameraPhoto
import com.openminis.app.data.photos.CameraRollRules
import com.openminis.app.data.photos.CameraRollSource
import com.openminis.app.data.photos.MinisCameraCapture
import com.openminis.app.data.photos.PhotoGrouper
import com.openminis.app.data.photos.TimelineItem
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

data class TodayDay(val date: LocalDate, val rows: List<TodayRow>)

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
)

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
        ),
    )
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    private val _draft = MutableStateFlow(prefs.getString(KEY_DRAFT, "").orEmpty())
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** One-shot UI events (toasts, permission prompts). */
    sealed interface Event {
        data class Toast(val res: Int) : Event
        data object NeedLocationPermission : Event
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
        load()
    }

    fun reload() { reloadRequests.value++ }

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

            // Notes.
            val notes = ArrayList<TodayNote>()
            for (d in dates) {
                val doc = store.readDay(d) ?: continue
                val idCounts = doc.entries.groupingBy { it.id }.eachCount()
                for (e in doc.entries) {
                    val atMs = e.at?.toInstant()?.toEpochMilli()
                        ?: d.atStartOfDay(zone).toInstant().toEpochMilli()
                    notes += TodayNote(e, d, atMs, emptyList(), editable = e.id != null && idCounts[e.id] == 1)
                }
            }
            // Photos attached to notes, resolved wherever they are in time.
            val attachedPaths = notes.flatMap { it.entry.photos }.toSet()
            val attached = roll.findByRelativePaths(attachedPaths)
            val resolvedNotes = notes.map { n -> n.copy(photos = n.entry.photos.map { it to attached[it] }) }

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
            val days = dates.mapNotNull { d ->
                val rows = byDay[d].orEmpty().map { item ->
                    when (item) {
                        is TimelineItem.Note -> TodayRow.Note(item.note)
                        // Cached place only: never wait on the network before showing photos.
                        is TimelineItem.PhotoGroup -> TodayRow.Group(TodayGroup(item.photos, if (showPlaces) groupPlaces[item.photos.first().id] else null))
                    }
                }
                if (rows.isEmpty() && d != today) null else TodayDay(d, rows)
            }
            val hasOlder = store.availableDays().any { it < start } || windowDays < MAX_WINDOW_DAYS
            _state.update {
                it.copy(loaded = true, days = days, access = access, hasOlder = hasOlder, today = today)
            }
            if (showPlaces) resolvePlaces(days)
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
                else if (!prefs.getBoolean(KEY_LOCATION_ASKED, false)) {
                    awaitingLocation = date to entry.id!!
                    events.send(Event.NeedLocationPermission)
                }
            }
        }
    }

    /** Result of the location permission prompt raised by [send]. */
    fun onLocationPermissionResult(granted: Boolean) {
        prefs.edit().putBoolean(KEY_LOCATION_ASKED, true).apply()
        val target = awaitingLocation
        awaitingLocation = null
        if (granted) {
            target?.let { (d, id) -> locate(d, id) }
        } else {
            setRecordLocation(false)
            events.trySend(Event.Toast(com.openminis.app.R.string.today_location_off_toast))
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

    // ── Settings (⋯ menu) ─────────────────────────────────────────────────

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
        private const val KEY_DRAFT = "draft"
        private const val KEY_PENDING = "pending"
        private const val KEY_INFLIGHT = "inflight"
        private const val KEY_RECORD_LOCATION = "recordLocation"
        private const val KEY_LOCATION_ASKED = "locationAsked"
        private const val KEY_ALBUMS = "includedAlbums"
        private const val INITIAL_DAYS = 7
        private const val OLDER_STEP_DAYS = 7
        private const val MAX_WINDOW_DAYS = 90
        private const val RELOAD_DEBOUNCE_MS = 500L
        private const val MAX_EXIF_READS = 5
    }
}
