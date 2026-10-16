package com.openminis.app.ui.today

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.journal.CheckIns
import com.openminis.app.data.journal.JournalEntry
import com.openminis.app.data.journal.JournalStore
import com.openminis.app.data.photos.CameraPhoto
import com.openminis.app.data.photos.CameraRollSource
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * What 探索 reads from Today (SPEC §20): the nature collection and the food
 * murmurs are Today's, built from caches it keeps. TodayViewModel provides them.
 */
interface ExploreSources {
    /** The collection as last built, to fall back on. */
    val lastCollection: List<CollectedSpecies>?
    suspend fun collection(): List<CollectedSpecies>
    /** The food murmurs, newest first; [days] = the journal already read, if the caller has it. */
    suspend fun foodNotes(days: List<Pair<LocalDate, List<JournalEntry>>>? = null): List<FoodNote>
    /** Whether the 碎碎念 model is on the phone (吃喝 needs it). */
    val murmurReady: Boolean
    /** Goes up whenever murmur tags change: new tags can mean new dishes. */
    val tagChanges: StateFlow<Int>
}

/**
 * [T-today-explore] The 探索 tab's state (SPEC §20). Built whole, off the
 * main thread, and kept for the life of the activity: the tab shows the last
 * one at once on every visit. It is built only while the tab shows: on
 * opening, and when the journal, the photos, the murmur tags or the day have
 * changed. Changes while it is hidden are caught up once, on the next visit.
 * Nothing is built before the tab is first opened.
 */
@OptIn(FlowPreview::class)
class ExploreViewModel(app: Application, private val sources: ExploreSources) : AndroidViewModel(app) {
    private val store = JournalStore.get(app)
    private val roll = CameraRollSource(app)
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val prefs = app.getSharedPreferences("today_explore", Context.MODE_PRIVATE)

    private val _data = MutableStateFlow<ExploreData?>(null)
    val data: StateFlow<ExploreData?> = _data.asStateFlow()

    /** Which of today's pages is showing. Kept across restarts for the day, so the day's few pages stay few. */
    private val _page = MutableStateFlow(0)
    val page: StateFlow<Int> = _page.asStateFlow()

    private val photoChanges = MutableStateFlow(0)
    /** Whether the tab is on screen; showing again also notices a day that has turned. */
    private val visible = MutableStateFlow(false)

    private data class Inputs(val journal: Long, val photos: Int, val tags: Int, val day: LocalDate)

    init {
        viewModelScope.launch { roll.changes().collect { photoChanges.update { it + 1 } } }
        viewModelScope.launch {
            var first = true
            combine(store.changes, photoChanges, sources.tagChanges, visible) { j, p, t, shown -> Inputs(j, p, t, LocalDate.now()).takeIf { shown } }
                .filterNotNull()
                .distinctUntilChanged()
                // The first build at once; after that, let a burst of changes (a photo shoot, a tagging run) settle.
                .debounce { if (first) 0L else SETTLE_MS }
                .conflate()
                .collect { inputs ->
                    first = false
                    runCatching { build(inputs.day) }
                        .onSuccess { d ->
                            if (_data.value?.today != d.today) _page.value = storedPage(d.today)
                            _data.value = d
                        }
                        .onFailure { AppLogger.warning(TAG, "build: ${it.message}") }
                }
        }
    }

    /** The tab came on screen (or the app came back to it) / went away. */
    fun setVisible(shown: Boolean) { visible.value = shown }

    fun turnTo(page: Int) {
        val today = _data.value?.today ?: return
        _page.value = page
        prefs.edit().putString(KEY_DAY, today.toString()).putInt(KEY_PAGE, page).apply()
    }

    private fun storedPage(today: LocalDate): Int =
        if (prefs.getString(KEY_DAY, null) == today.toString()) prefs.getInt(KEY_PAGE, 0) else 0

    private suspend fun build(today: LocalDate): ExploreData = withContext(Dispatchers.IO) {
        // One read of the journal for the notes and the food; the collection keeps its own per-day index.
        val days = store.availableDays().sorted().map { it to store.readDay(it)?.entries.orEmpty() }
        val speciesJob = async { runCatching { sources.collection() }.getOrDefault(sources.lastCollection.orEmpty()) }
        val food = runCatching { sources.foodNotes(days) }.getOrDefault(emptyList())
        val notes = days.flatMap { (date, entries) -> notesOf(date, entries) }
        val species = speciesJob.await()
        val pages = Explore.pages(notes, today)
        val firsts = Explore.firsts(species, food)
        val foodCover = food.firstOrNull { it.photos.isNotEmpty() }?.photos?.first()
        // Species photos are already looked up by the collection; the rest are looked up here.
        val kindPhotos = species.flatMap { it.first.photos }.associateBy { it.relativePath }
        val photos = roll.findByRelativePaths(
            pages.flatMap { it.note.photos.take(3) } + listOfNotNull(foodCover) + firsts.filter { !it.species }.mapNotNull { it.photo },
        ) + firsts.filter { it.species }.mapNotNull { f -> f.photo?.let { p -> kindPhotos[p]?.let { p to it } } }
        ExploreData(
            today = today,
            pages = pages,
            facts = Explore.facts(notes, zone),
            firsts = firsts,
            speciesCount = species.size,
            natureCover = species.firstOrNull()?.photos?.firstOrNull(),
            showFood = sources.murmurReady || food.isNotEmpty(),
            food = food,
            wishes = Explore.wishes(notes).filter { it.note.wish != Explore.WISH_DROPPED },
            foodCover = foodCover?.let(photos::get),
            photos = photos,
        )
    }

    /**
     * Tick a wish off ([Explore.WISH_DONE]), let it go ([Explore.WISH_DROPPED]),
     * or (null) put it back. Written into the entry: what became of a wish is the
     * author's own word, like a mood they set. The page rebuilds from the change.
     */
    fun setWish(note: ExploreNote, state: String?) {
        viewModelScope.launch {
            val on = if (state == Explore.WISH_DONE) LocalDate.now().toString() else null
            store.setAttrs(note.date, note.id, mapOf(JournalEntry.KEY_WISH to state, JournalEntry.KEY_WISH_ON to on))
        }
    }

    /** One day's notes for the "that day" page, oldest first, with their photos. */
    suspend fun day(date: LocalDate): Pair<List<ExploreNote>, Map<String, CameraPhoto>> = withContext(Dispatchers.IO) {
        val notes = notesOf(date, store.readDay(date)?.entries.orEmpty())
        notes to roll.findByRelativePaths(notes.flatMap { it.photos })
    }

    /** A day's notes as 探索 sees them: no check-ins, nothing empty, oldest first. */
    private fun notesOf(date: LocalDate, entries: List<JournalEntry>): List<ExploreNote> =
        entries
            .filter { it.id != null && !CheckIns.isCheckIn(it) && (it.body.isNotBlank() || it.photos.isNotEmpty()) }
            .map { e ->
                ExploreNote(
                    e.id!!, date, e.at?.toInstant()?.toEpochMilli() ?: date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    e.body, e.photos, e.place, e.attrs[JournalEntry.KEY_WISH], e.attrs[JournalEntry.KEY_WISH_ON]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                )
            }
            .sortedBy { it.atMs }

    companion object {
        private const val TAG = "ExploreVM"
        private const val KEY_DAY = "day"
        private const val KEY_PAGE = "page"
        private const val SETTLE_MS = 600L
    }
}
