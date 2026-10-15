package com.openminis.app.data.journal

import android.content.Context
import android.os.FileObserver
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * [T-today-tab] Reads and writes the journal day files under
 * `<filesDir>/minis-global/journal/YYYY/MM/YYYY-MM-DD.md`, which the sandbox
 * sees as `/var/minis/journal/...` (docs/design/today/SPEC.md §4).
 *
 * Every edit re-reads the file inside [lock] and rewrites only the target
 * block, so a change made meanwhile in Obsidian or the shell is never lost.
 */
class JournalStore(
    val root: File,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now(zone()).truncatedTo(ChronoUnit.SECONDS) },
) {
    private val lock = Mutex()
    private val _changes = MutableStateFlow(0L)

    /** Bumps on every write here and on any change seen in the watched folders. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** The store's clock, so callers (and tests) agree on what "now" and "today" are. */
    fun now(): OffsetDateTime = clock()

    fun fileFor(date: LocalDate): File =
        File(root, "%04d/%02d/%s.md".format(date.year, date.monthValue, date))

    suspend fun readDay(date: LocalDate): JournalDocument? = withContext(Dispatchers.IO) {
        val f = fileFor(date)
        if (!f.isFile) null else JournalCodec.parse(f.readText())
    }

    /** Days that have a file, newest first. */
    suspend fun availableDays(): List<LocalDate> = withContext(Dispatchers.IO) {
        root.walkTopDown().maxDepth(3)
            .filter { it.isFile && it.name.endsWith(".md") }
            .mapNotNull { runCatching { LocalDate.parse(it.name.removeSuffix(".md")) }.getOrNull() }
            .sortedDescending()
            .toList()
    }

    /**
     * New entry, appended to the file of its own local day (§4.1). [at] defaults
     * to now; the agent may backfill a past moment ("log yesterday's film").
     * [by] marks who wrote it — null for the user, [JournalEntry.BY_MINIS] for the agent.
     */
    suspend fun add(
        body: String,
        photos: List<String> = emptyList(),
        at: OffsetDateTime? = null,
        by: String? = null,
    ): JournalEntry {
        val time = at ?: clock()
        var entry = JournalEntry.create(Ulid.generate(time.toInstant().toEpochMilli()), time, body.trim(), photos)
        if (by != null) entry = entry.withAttrs(mapOf(JournalEntry.KEY_BY to by))
        mutate(time.toLocalDate()) { text -> JournalCodec.append(text, time.toLocalDate(), entry) }
        return entry
    }

    /**
     * Replace an entry's text, and its [photos] when given (the app's edit
     * can add and remove them). [by] records who wrote the new text: an edit
     * in the app clears the agent mark (the words are the user's again), an
     * agent edit sets it.
     */
    suspend fun editBody(date: LocalDate, id: String, body: String, by: String? = null, photos: List<String>? = null): Boolean =
        update(date, id) {
            val attrs = linkedMapOf(JournalEntry.KEY_EDITED to JournalCodec.formatTime(clock()), JournalEntry.KEY_BY to by)
            if (photos != null) attrs[JournalEntry.KEY_PHOTOS] = photos.distinct().joinToString(",").ifEmpty { null }
            it.copy(body = body.trim()).withAttrs(attrs)
        }

    /**
     * The agent's edit (§4.6): any of new text, a new moment [at] (which may
     * move the entry to another day's file) and [addPhotos] appended to its
     * photos. [by] is recorded only when the text changes — moving or adding
     * a photo does not make the words someone else's. Returns the day the
     * entry now lives on, or null if it could not be edited.
     */
    suspend fun edit(
        date: LocalDate,
        id: String,
        body: String? = null,
        at: OffsetDateTime? = null,
        addPhotos: List<String> = emptyList(),
        by: String? = null,
    ): LocalDate? {
        val old = readDay(date)?.entries?.singleOrNull { it.id == id } ?: return null
        val attrs = linkedMapOf<String, String?>(JournalEntry.KEY_EDITED to JournalCodec.formatTime(clock()))
        if (body != null) attrs[JournalEntry.KEY_BY] = by
        if (at != null) attrs[JournalEntry.KEY_AT] = JournalCodec.formatTime(at)
        val photos = (old.photos + addPhotos).distinct()
        if (photos != old.photos) attrs[JournalEntry.KEY_PHOTOS] = photos.joinToString(",")
        fun transform(e: JournalEntry) = e.copy(
            heading = at?.let(JournalCodec::headingFor) ?: e.heading,
            body = body?.trim() ?: e.body,
        ).withAttrs(attrs)

        val target = at?.toLocalDate() ?: date
        if (target == date) return if (update(date, id, ::transform)) date else null
        // Another day: write the new copy first, so a failure in between
        // leaves a duplicate rather than losing the entry.
        mutate(target) { text -> JournalCodec.append(text, target, transform(old)) }
        delete(date, id)
        return target
    }

    /** Locate an entry by id across all day files, newest day first. */
    suspend fun find(id: String): Pair<LocalDate, JournalEntry>? {
        for (day in availableDays()) {
            val e = readDay(day)?.entries?.firstOrNull { it.id == id } ?: continue
            return day to e
        }
        return null
    }

    suspend fun setAttrs(date: LocalDate, id: String, attrs: Map<String, String?>): Boolean =
        update(date, id) { it.withAttrs(attrs) }

    suspend fun delete(date: LocalDate, id: String): Boolean {
        var ok = false
        mutate(date) { text ->
            text ?: return@mutate null
            JournalCodec.replace(text, id, null).also { ok = it != null }
        }
        return ok
    }

    private suspend fun update(date: LocalDate, id: String, transform: (JournalEntry) -> JournalEntry): Boolean {
        var ok = false
        mutate(date) { text ->
            text ?: return@mutate null
            val entry = JournalCodec.parse(text).entries.singleOrNull { it.id == id } ?: return@mutate null
            JournalCodec.replace(text, id, transform(entry)).also { ok = it != null }
        }
        return ok
    }

    /** Set front matter keys of a day file (weather, §15); entries are left byte for byte. */
    suspend fun setFrontMatter(date: LocalDate, updates: Map<String, String?>) =
        mutate(date) { text -> JournalCodec.withFrontMatter(text?.takeIf { it.isNotBlank() } ?: JournalCodec.frontMatter(date), updates) }

    // ── Daily check-ins (§14) ─────────────────────────────────────────────

    val habitsFile: File get() = File(root, HabitCodec.FILE_NAME)

    suspend fun readHabits(): List<Habit> = withContext(Dispatchers.IO) {
        val f = habitsFile
        if (!f.isFile) emptyList() else HabitCodec.parse(f.readText())
    }

    suspend fun addHabit(name: String, target: Int?, unit: String?, days: Set<java.time.DayOfWeek>?): Habit {
        val habit = Habit.create(Ulid.generate(clock().toInstant().toEpochMilli()), name, target, unit, days)
        mutateFile(habitsFile) { text -> HabitCodec.append(text, habit) }
        return habit
    }

    /** Rewrite one habit's line (rename, new target, archive); the habit as written, or null if it could not be. */
    suspend fun updateHabit(id: String, transform: (Habit) -> Habit): Habit? {
        var written: Habit? = null
        mutateFile(habitsFile) { text ->
            text ?: return@mutateFile null
            val habit = HabitCodec.parse(text).singleOrNull { it.id == id } ?: return@mutateFile null
            val next = transform(habit)
            HabitCodec.replace(text, id, next)?.also { written = next }
        }
        return written
    }

    /** Delete = archive (§14.2): the line stays so past check-ins keep their name. */
    suspend fun archiveHabit(id: String): Habit? =
        updateHabit(id) { it.withAttrs(mapOf(Habit.KEY_ARCHIVED to clock().toLocalDate().toString())) }

    /** Undo of [archiveHabit]. */
    suspend fun unarchiveHabit(id: String): Habit? = updateHabit(id) { it.withAttrs(mapOf(Habit.KEY_ARCHIVED to null)) }

    /** Today's check-in total per habit id. */
    suspend fun todayTotals(): Map<String, Int> = CheckIns.totals(readDay(clock().toLocalDate())?.entries.orEmpty())

    /** "Done" for today without a count: fill [habit] up to its goal, never lowering it. Returns the new total. */
    suspend fun completeCheckIn(habit: Habit, by: String? = null): Int {
        val after = maxOf(todayTotals()[habit.id] ?: 0, habit.goal)
        setCheckIn(clock().toLocalDate(), habit, after, by)
        return after
    }

    /**
     * Set the day's total for [habit] to [total] (§14.2). The check-in entries
     * of that habit are summed; the last one absorbs the change, the others
     * are only touched when the new total is below them. 0 removes them all.
     * Callers enforce "today only" (§14.1); this is also what restore and tests use.
     */
    suspend fun setCheckIn(date: LocalDate, habit: Habit, total: Int, by: String? = null) {
        val habitId = habit.id ?: return
        val now = clock()
        mutate(date) { text ->
            val entries = text?.let { JournalCodec.parse(it).entries }.orEmpty()
                .filter { CheckIns.habitId(it) == habitId && it.id != null }
            var next = text
            if (total <= 0) {
                for (e in entries) next = next?.let { JournalCodec.replace(it, e.id!!, null) } ?: return@mutate null
                return@mutate next
            }
            if (entries.isEmpty()) {
                // A check-in made today is stamped now; another day keeps its own date.
                val at = if (now.toLocalDate() == date) now else date.atTime(12, 0).atZone(zone()).toOffsetDateTime()
                var e = JournalEntry.create(Ulid.generate(at.toInstant().toEpochMilli()), at, CheckIns.body(habit, total))
                e = e.withAttrs(mapOf(CheckIns.KEY_HABIT to habitId, CheckIns.KEY_DONE to total.toString(), JournalEntry.KEY_BY to by))
                return@mutate JournalCodec.append(text, date, e)
            }
            val others = entries.dropLast(1)
            val keep = if (others.sumOf(CheckIns::done) < total) {
                others
            } else {
                for (e in others) next = next?.let { JournalCodec.replace(it, e.id!!, null) } ?: return@mutate null
                emptyList()
            }
            val last = entries.last()
            val lastDone = total - keep.sumOf(CheckIns::done)
            val updated = last.copy(body = CheckIns.body(habit, total)).withAttrs(
                mapOf(
                    CheckIns.KEY_DONE to lastDone.toString(),
                    JournalEntry.KEY_EDITED to JournalCodec.formatTime(now),
                    JournalEntry.KEY_BY to by,
                ),
            )
            next?.let { JournalCodec.replace(it, last.id!!, updated) }
        }
    }

    /** Read-modify-write under the lock; [edit] returns null to leave the file alone. */
    private suspend fun mutate(date: LocalDate, edit: (String?) -> String?) = mutateFile(fileFor(date), edit)

    private suspend fun mutateFile(f: File, edit: (String?) -> String?) = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = if (f.isFile) f.readText() else null
            val next = edit(current) ?: return@withLock
            if (next != current) {
                writeAtomically(f, next)
                _changes.value = _changes.value + 1
            }
        }
    }

    /** Restore merge of habits.md (§14.5). */
    suspend fun mergeHabits(incoming: String): Int {
        var added = 0
        mutateFile(habitsFile) { text -> HabitCodec.merge(text, incoming)?.also { added = it.second }?.first }
        return added
    }

    /** Replace a whole day file (restore). Same atomic write + change signal. */
    suspend fun writeDay(date: LocalDate, text: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            writeAtomically(fileFor(date), text)
            _changes.value = _changes.value + 1
        }
    }

    // ── Watching for edits made outside the app (§4.5) ────────────────────

    private val observers = mutableMapOf<String, FileObserver>()

    /**
     * Watch the month folders of [dates] (FileObserver is not recursive). Edits
     * to other months are picked up on the next resume, which reloads anyway.
     */
    @Synchronized
    fun watch(dates: Collection<LocalDate>) {
        // The root holds habits.md (§14.2); day files live in the month folders.
        val dirs = dates.map { fileFor(it).parentFile!! }.toSet() + root
        for (dir in dirs) {
            if (observers.containsKey(dir.path)) continue
            dir.mkdirs()
            @Suppress("DEPRECATION")
            val obs = object : FileObserver(dir.path, CLOSE_WRITE or MOVED_TO or DELETE or MOVED_FROM) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || !path.endsWith(".md")) return
                    _changes.value = _changes.value + 1
                }
            }
            obs.startWatching()
            observers[dir.path] = obs
        }
    }

    @Synchronized
    fun stopWatching() {
        observers.values.forEach { it.stopWatching() }
        observers.clear()
    }

    companion object {
        private const val TAG = "JournalStore"

        @Volatile private var instance: JournalStore? = null

        fun rootFor(context: Context): File = File(context.filesDir, "minis-global/journal")

        /** Where the sandbox sees the journal (SPEC §4.1). Read-only to the agent. */
        const val LINUX_DIR = "/var/minis/journal"

        /** True for [LINUX_DIR] and anything under it, after resolving `.`/`..`. */
        fun isJournalLinuxPath(linuxPath: String): Boolean {
            val p = File(linuxPath).normalize().path
            return p == LINUX_DIR || p.startsWith("$LINUX_DIR/")
        }

        fun get(context: Context): JournalStore =
            instance ?: synchronized(this) {
                instance ?: JournalStore(rootFor(context.applicationContext)).also { instance = it }
            }

        /** Temp file in the same directory, fsync, then rename over the target. */
        fun writeAtomically(target: File, text: String) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                AppLogger.warning(TAG, "rename failed for ${target.name}; copying instead")
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }
}
