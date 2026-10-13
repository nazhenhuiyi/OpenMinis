package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.journal.CheckIns
import com.openminis.app.data.journal.Habit
import com.openminis.app.data.journal.JournalEntry
import com.openminis.app.data.journal.JournalStore
import com.openminis.app.data.photos.MinisCameraCapture
import com.openminis.app.data.journal.TodayLocation
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.ui.today.TodayViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * [T-today-tab] `journal` — the agent's way to read and write the user's
 * Today journal (docs/design/today/SPEC.md §4.6).
 *
 * Writes go through [JournalStore], never raw file edits: the store keeps
 * ids, timestamps and the metadata line intact, which editing, restore merge
 * and backup all depend on. Raw file_write/file_edit on /var/minis/journal stay
 * refused and point here. Everything the agent writes is stamped `by=minis`, so
 * the timeline can show which words were not the user's.
 */
object JournalTool {
    const val NAME = "journal"
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    /** Copies land in DCIM/Minis/; anything else was already in the library and is only referenced. */
    private fun photoNote(photos: List<String>): String {
        if (photos.isEmpty()) return ""
        val (saved, existing) = photos.partition { it.startsWith(MinisCameraCapture.LIBRARY_DIR + "/") }
        return buildList {
            if (saved.isNotEmpty()) add("${saved.size} photo(s) newly saved to the photo library: ${saved.joinToString(",")}")
            if (existing.isNotEmpty()) add("${existing.size} photo(s) already in the photo library, attached as they are: ${existing.joinToString(",")}")
        }.joinToString("; ", prefix = ", with ")
    }

    /** "30/50 个" for counted habits, done / not done for tick-boxes. */
    private fun progress(h: Habit, done: Int): String = h.progressText(done) ?: if (done > 0) "done" else "not done"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read and write the user's Today journal (/var/minis/journal) — their own diary of short notes and photos. " +
            "Actions: list (entries of one day, with ids), add (a new entry), edit (change an entry's text, move it to another time, and/or attach photos), delete (remove an entry). " +
            "When the user asks to journal a photo they sent in chat, pass its /var/minis/... path in photos: it is saved to the phone's photo library and shown with the entry. " +
            "Entries you add or edit are marked as written by Minis in the user's timeline. " +
            "Only write when the user asks you to, or clearly agrees; keep their voice and language, and never rewrite or delete their words on your own initiative. " +
            "Use list first to get an entry's id before edit or delete, unless you already have it (a quoted entry carries its id). " +
            "Daily check-ins (habits such as '20 push-ups'): habits lists the user's check-in items and today's progress; " +
            "check_in records progress for TODAY only (past days cannot be checked in). " +
            "Check-in items themselves are defined by the user in the Today tab; you cannot create or change them.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Read today's journal', 'Log yesterday's movie night'). Use the same language as the user."),
            "action" to AgentToolParam("string", "What to do.", enumValues = listOf("list", "add", "edit", "delete", "habits", "check_in")),
            "date" to AgentToolParam("string", "Local date YYYY-MM-DD. list: the day to read (default today). add: the day the entry belongs to (default today). edit: move the entry to this day."),
            "time" to AgentToolParam("string", "add: local time HH:mm the entry is about; omit it for something happening now (today only). Required when backfilling another day. edit: move the entry to this time."),
            "content" to AgentToolParam("string", "add: the entry text, plain Markdown, written as the user would write it. edit: the new text; omit to keep the text as it is."),
            "photos" to AgentToolParam("string", "add/edit: comma-separated image paths in the sandbox (e.g. /var/minis/attachments/uploads/photo-123.jpg) to attach. edit adds them to the entry's existing photos."),
            "id" to AgentToolParam("string", "edit/delete: the entry id from list."),
            "habit" to AgentToolParam("string", "check_in: the habit id or exact name from habits."),
            "count" to AgentToolParam("integer", "check_in: how much to ADD to today's total (negative to correct a mistake). Omit to mark the habit fully done for today."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "date", "time", "content", "photos", "id", "habit", "count"),
    )

    suspend fun execute(context: Context, argsJson: String, sessionId: String): ToolExecutionResult {
        val store = JournalStore.get(context)
        return execute(
            store, argsJson, ZoneId.systemDefault(),
            importPhoto = { path ->
                withContext(Dispatchers.IO) {
                    val src = PRootKernel.resolveSessionHostPath(sessionId, File(path).normalize().path, context)
                        ?.takeIf { it.isFile } ?: return@withContext null
                    MinisCameraCapture.importFile(context, src)
                }
            },
            // Like a note sent from the Today tab (§6.1): the entry is written
            // first, coordinates and place are filled in afterwards.
            onAddedNow = { day, id ->
                if (TodayViewModel.recordsLocation(context) && TodayLocation.hasPermission(context)) {
                    backgroundScope.launch {
                        val fix = TodayLocation.fix(context) ?: return@launch
                        store.setAttrs(day, id, TodayLocation.attrsFor(fix))
                        TodayLocation.placeName(context, fix.lat, fix.lng)?.let {
                            store.setAttrs(day, id, mapOf(JournalEntry.KEY_PLACE to it))
                        }
                    }
                }
            },
        )
    }

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [importPhoto] turns one sandbox image into a `photos=` path, or null if
     * there is no such image. [onAddedNow] runs after an add about the present
     * moment (not a backfill), for the location lookup.
     */
    suspend fun execute(
        store: JournalStore,
        argsJson: String,
        zone: ZoneId,
        importPhoto: suspend (String) -> String? = { null },
        onAddedNow: (LocalDate, String) -> Unit = { _, _ -> },
    ): ToolExecutionResult {
        val r = run(store, argsJson, zone, importPhoto, onAddedNow)
        // The system prompt carries only the date (it is cached per day), so
        // every result says what time it is: a wrong time is then visible.
        val now = store.now().atZoneSameInstant(zone)
        return r.copy(output = r.output + "\n\n(Now: ${NOW.format(now)} ${zone.id})")
    }

    private val NOW = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private suspend fun run(
        store: JournalStore,
        argsJson: String,
        zone: ZoneId,
        importPhoto: suspend (String) -> String?,
        onAddedNow: (LocalDate, String) -> Unit,
    ): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (e: Exception) {
            return ToolExecutionResult("Error: invalid arguments: ${e.message}", false)
        }
        val title = args.optString("tool_title").ifBlank { "Journal" }
        fun fail(msg: String) = ToolExecutionResult("Error: $msg", false, toolTitle = title)
        fun date(): LocalDate? = args.optString("date").takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDate.parse(it.trim()) }.getOrNull() ?: return null
        } ?: store.now().toLocalDate()
        val photoArgs: List<String> = when (val p = args.opt("photos")) {
            is JSONArray -> (0 until p.length()).map { p.optString(it) }
            is String -> p.split(',')
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }
        // Imported only after the other arguments check out, so a bad date or
        // id does not leave a copy in the library.
        suspend fun importPhotos(): Pair<List<String>, String?> {
            val out = mutableListOf<String>()
            for (p in photoArgs) out += importPhoto(p) ?: return out to "no image at $p (pass the /var/minis/... path of a jpg/png/webp/heic file)"
            return out to null
        }

        return when (args.optString("action").trim().lowercase()) {
            "list" -> {
                val day = date() ?: return fail("date must be YYYY-MM-DD")
                val doc = store.readDay(day)
                if (doc == null || doc.entries.isEmpty()) {
                    return ToolExecutionResult("No journal entries on $day.", true, toolTitle = title)
                }
                val out = buildString {
                    append("Journal for $day (${doc.entries.size} entries):\n")
                    for (e in doc.entries) {
                        append("\n- id=").append(e.id ?: "(none)")
                        append(" time=").append(e.at?.let { HM.format(it) } ?: e.heading)
                        e.place?.let { append(" place=").append(it) }
                        if (e.photos.isNotEmpty()) append(" photos=").append(e.photos.joinToString(","))
                        if (e.writtenByMinis) append(" by=minis")
                        CheckIns.habitId(e)?.let { append(" check_in habit=").append(it).append(" done=").append(CheckIns.done(e)) }
                        append("\n  ").append(e.body.replace("\n", "\n  "))
                    }
                }
                ToolExecutionResult(out, true, toolTitle = title)
            }
            "add" -> {
                val content = args.optString("content").trim()
                if (content.isEmpty()) return fail("content is required for add")
                val day = date() ?: return fail("date must be YYYY-MM-DD")
                val timeArg = args.optString("time").trim()
                val isNow = timeArg.isEmpty()
                val time = if (isNow) {
                    // "Now" only makes sense today; another day's now-o'clock is a guess.
                    if (day != store.now().toLocalDate()) return fail("time is required when adding to another day than today")
                    store.now().toLocalTime()
                } else {
                    runCatching { LocalTime.parse(timeArg) }.getOrNull() ?: return fail("time must be HH:mm")
                }
                val at = OffsetDateTime.of(day, time, zone.rules.getOffset(day.atTime(time)))
                if (at.isAfter(store.now().plusMinutes(5))) return fail("cannot add an entry in the future")
                // A retried or repeated call must not write the same words twice.
                store.readDay(day)?.entries?.firstOrNull { it.writtenByMinis && it.body == content }?.let {
                    return ToolExecutionResult(
                        "Not added: entry id=${it.id} on $day already has this exact text. Use edit to change its time or attach photos.",
                        true, toolTitle = title,
                    )
                }
                val (photos, photoError) = importPhotos()
                if (photoError != null) return fail(photoError)
                val e = store.add(content, photos, at = at, by = JournalEntry.BY_MINIS)
                if (isNow) onAddedNow(day, e.id!!)
                ToolExecutionResult("Added journal entry id=${e.id} on $day at ${HM.format(at)}${photoNote(photos)}.", true, toolTitle = title)
            }
            "edit" -> {
                val id = args.optString("id").trim().ifEmpty { return fail("id is required for edit") }
                val content = args.optString("content").trim().takeIf { it.isNotEmpty() }
                val dateArg = args.optString("date").trim()
                val timeArg = args.optString("time").trim()
                if (content == null && dateArg.isEmpty() && timeArg.isEmpty() && photoArgs.isEmpty()) {
                    return fail("edit needs at least one of content, date, time, photos")
                }
                val (day, found) = store.find(id) ?: return fail("no journal entry with id=$id")
                if (CheckIns.isCheckIn(found)) return fail("entry id=$id is a check-in; use action check_in to change its count")
                val at = if (dateArg.isEmpty() && timeArg.isEmpty()) null else {
                    val old = found.at ?: return fail("entry id=$id has no time to move from")
                    val d = if (dateArg.isEmpty()) old.toLocalDate() else runCatching { LocalDate.parse(dateArg) }.getOrNull()
                        ?: return fail("date must be YYYY-MM-DD")
                    val t = if (timeArg.isEmpty()) old.toLocalTime() else runCatching { LocalTime.parse(timeArg) }.getOrNull()
                        ?: return fail("time must be HH:mm")
                    OffsetDateTime.of(d, t, zone.rules.getOffset(d.atTime(t)))
                }
                if (at != null && at.isAfter(store.now().plusMinutes(5))) return fail("cannot move an entry into the future")
                val (photos, photoError) = importPhotos()
                if (photoError != null) return fail(photoError)
                val now = store.edit(day, id, body = content, at = at, addPhotos = photos, by = JournalEntry.BY_MINIS)
                    ?: return fail("entry id=$id could not be edited (its id may appear twice in the file)")
                val where = if (at != null) " now on $now at ${HM.format(at)}" else " on $now"
                // "Ask Minis" hands the entry's own photos to the chat; passing
                // them back is a no-op and should read as one.
                val (onEntry, added) = photos.partition { it in found.photos }
                val already = if (onEntry.isEmpty()) "" else "; ${onEntry.size} photo(s) were already on this entry, unchanged: ${onEntry.joinToString(",")}"
                ToolExecutionResult("Edited journal entry id=$id$where${photoNote(added)}$already.", true, toolTitle = title)
            }
            "delete" -> {
                val id = args.optString("id").trim().ifEmpty { return fail("id is required for delete") }
                val (day, _) = store.find(id) ?: return fail("no journal entry with id=$id")
                if (!store.delete(day, id)) return fail("entry id=$id could not be deleted")
                ToolExecutionResult("Deleted journal entry id=$id from $day. Photos stay in the photo library.", true, toolTitle = title)
            }
            "habits" -> {
                val today = store.now().toLocalDate()
                val habits = store.readHabits().filter { !it.archived }
                if (habits.isEmpty()) {
                    return ToolExecutionResult("The user has no check-in items. They can add them in the Today tab (⋯ → Daily check-ins).", true, toolTitle = title)
                }
                val totals = CheckIns.totals(store.readDay(today)?.entries.orEmpty())
                val out = buildString {
                    append("Check-in items (today is $today, ${today.dayOfWeek.name.lowercase()}):\n")
                    for (h in habits) {
                        append("\n- id=").append(h.id).append(" name=").append(h.name)
                        h.target?.let { append(" target=").append(it) }
                        h.unit?.let { append(" unit=").append(it) }
                        h.days?.let { d -> append(" days=").append(Habit.daysAttr(d)) }
                        if (!h.dueOn(today)) append(" (not due today)")
                        val done = totals[h.id] ?: 0
                        append(" today=").append(progress(h, done))
                    }
                }
                ToolExecutionResult(out, true, toolTitle = title)
            }
            "check_in" -> {
                val key = args.optString("habit").trim().ifEmpty { return fail("habit is required for check_in") }
                if (args.optString("date").isNotBlank() && date() != store.now().toLocalDate()) {
                    return fail("check-ins can only be recorded for today; past days cannot be filled in")
                }
                val habits = store.readHabits().filter { !it.archived }
                val habit = habits.firstOrNull { it.id == key } ?: habits.firstOrNull { it.name.equals(key, ignoreCase = true) }
                    ?: return fail("no check-in item '$key'; use action habits to see the ids")
                val today = store.now().toLocalDate()
                val before = CheckIns.totals(store.readDay(today)?.entries.orEmpty())[habit.id] ?: 0
                val after = if (args.has("count") && !args.isNull("count")) {
                    val n = args.optInt("count", Int.MIN_VALUE)
                    if (n == Int.MIN_VALUE) return fail("count must be an integer")
                    if (habit.target == null && n > 0) 1 else (before + n).coerceAtLeast(0)
                } else {
                    maxOf(before, habit.goal)
                }
                store.setCheckIn(today, habit, after, by = JournalEntry.BY_MINIS)
                ToolExecutionResult("Checked in ${habit.name} for $today: ${progress(habit, after)}.", true, toolTitle = title)
            }
            else -> fail("action must be one of list, add, edit, delete, habits, check_in")
        }
    }
}
