package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.journal.JournalEntry
import com.openminis.app.data.journal.JournalStore
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read and write the user's Today journal (/var/minis/journal) — their own diary of short notes and photos. " +
            "Actions: list (entries of one day, with ids), add (a new entry), edit (replace an entry's text), delete (remove an entry). " +
            "Entries you add or edit are marked as written by Minis in the user's timeline. " +
            "Only write when the user asks you to, or clearly agrees; keep their voice and language, and never rewrite or delete their words on your own initiative. " +
            "Use list first to get an entry's id before edit or delete.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Read today's journal', 'Log yesterday's movie night'). Use the same language as the user."),
            "action" to AgentToolParam("string", "What to do.", enumValues = listOf("list", "add", "edit", "delete")),
            "date" to AgentToolParam("string", "Local date YYYY-MM-DD. list: the day to read (default today). add: the day the entry belongs to (default today)."),
            "time" to AgentToolParam("string", "add only: local time HH:mm the entry is about (default now). Use it to backfill something that happened earlier."),
            "content" to AgentToolParam("string", "add/edit: the entry text, plain Markdown, written as the user would write it."),
            "id" to AgentToolParam("string", "edit/delete: the entry id from list."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "date", "time", "content", "id"),
    )

    suspend fun execute(context: Context, argsJson: String): ToolExecutionResult =
        execute(JournalStore.get(context), argsJson, ZoneId.systemDefault())

    suspend fun execute(store: JournalStore, argsJson: String, zone: ZoneId): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (e: Exception) {
            return ToolExecutionResult("Error: invalid arguments: ${e.message}", false)
        }
        val title = args.optString("tool_title").ifBlank { "Journal" }
        fun fail(msg: String) = ToolExecutionResult("Error: $msg", false, toolTitle = title)
        fun date(): LocalDate? = args.optString("date").takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDate.parse(it.trim()) }.getOrNull() ?: return null
        } ?: LocalDate.now(zone)

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
                val time = if (timeArg.isEmpty()) {
                    LocalTime.now(zone).truncatedTo(ChronoUnit.SECONDS)
                } else {
                    runCatching { LocalTime.parse(timeArg) }.getOrNull() ?: return fail("time must be HH:mm")
                }
                val at = OffsetDateTime.of(day, time, zone.rules.getOffset(day.atTime(time)))
                if (at.isAfter(OffsetDateTime.now(zone).plusMinutes(5))) return fail("cannot add an entry in the future")
                val e = store.add(content, at = at, by = JournalEntry.BY_MINIS)
                ToolExecutionResult("Added journal entry id=${e.id} on $day at ${HM.format(at)}.", true, toolTitle = title)
            }
            "edit" -> {
                val id = args.optString("id").trim().ifEmpty { return fail("id is required for edit") }
                val content = args.optString("content").trim()
                if (content.isEmpty()) return fail("content is required for edit")
                val (day, _) = store.find(id) ?: return fail("no journal entry with id=$id")
                if (!store.editBody(day, id, content, by = JournalEntry.BY_MINIS)) {
                    return fail("entry id=$id could not be edited (its id may appear twice in the file)")
                }
                ToolExecutionResult("Edited journal entry id=$id on $day.", true, toolTitle = title)
            }
            "delete" -> {
                val id = args.optString("id").trim().ifEmpty { return fail("id is required for delete") }
                val (day, _) = store.find(id) ?: return fail("no journal entry with id=$id")
                if (!store.delete(day, id)) return fail("entry id=$id could not be deleted")
                ToolExecutionResult("Deleted journal entry id=$id from $day. Photos stay in the photo library.", true, toolTitle = title)
            }
            else -> fail("action must be one of list, add, edit, delete")
        }
    }
}
