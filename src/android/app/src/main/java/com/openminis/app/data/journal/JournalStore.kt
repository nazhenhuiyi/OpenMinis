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
     * Replace an entry's text. [by] records who wrote the new text: an edit in
     * the app clears the agent mark (the words are the user's again), an agent
     * edit sets it.
     */
    suspend fun editBody(date: LocalDate, id: String, body: String, by: String? = null): Boolean =
        update(date, id) {
            it.copy(body = body.trim()).withAttrs(
                mapOf(JournalEntry.KEY_EDITED to JournalCodec.formatTime(clock()), JournalEntry.KEY_BY to by),
            )
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

    /** Read-modify-write under the lock; [edit] returns null to leave the file alone. */
    private suspend fun mutate(date: LocalDate, edit: (String?) -> String?) = withContext(Dispatchers.IO) {
        lock.withLock {
            val f = fileFor(date)
            val current = if (f.isFile) f.readText() else null
            val next = edit(current) ?: return@withLock
            if (next != current) {
                writeAtomically(f, next)
                _changes.value = _changes.value + 1
            }
        }
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
        val dirs = dates.map { fileFor(it).parentFile!! }.toSet()
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
