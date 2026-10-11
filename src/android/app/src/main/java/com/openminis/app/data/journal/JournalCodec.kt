package com.openminis.app.data.journal

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * [T-today-tab] One journal entry ("碎碎念"), as stored in
 * `/var/minis/journal/YYYY/MM/YYYY-MM-DD.md` (docs/design/today/SPEC.md §4).
 *
 * [attrs] keeps EVERY metadata key in file order, including ones this build
 * does not know, so a rewrite never drops fields a newer version added (§4.4).
 */
data class JournalEntry(
    val heading: String,
    val attrs: LinkedHashMap<String, String>,
    val body: String,
) {
    val id: String? get() = attrs[KEY_ID]?.takeIf { it.isNotBlank() }
    val at: OffsetDateTime? get() = attrs[KEY_AT]?.let(JournalCodec::parseTime)
    val edited: OffsetDateTime? get() = attrs[KEY_EDITED]?.let(JournalCodec::parseTime)
    val lat: Double? get() = attrs[KEY_LAT]?.toDoubleOrNull()
    val lng: Double? get() = attrs[KEY_LNG]?.toDoubleOrNull()
    val accuracyM: Int? get() = attrs[KEY_ACC]?.toDoubleOrNull()?.toInt()
    val place: String? get() = attrs[KEY_PLACE]?.takeIf { it.isNotBlank() }

    /** Who last wrote the text: absent = the user, [BY_MINIS] = the agent. */
    val writtenByMinis: Boolean get() = attrs[KEY_BY] == BY_MINIS

    /** Relative paths inside the system photo library, e.g. `DCIM/Minis/MINIS_20261011_162031.jpg`. */
    val photos: List<String>
        get() = attrs[KEY_PHOTOS]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** The time a restore merge compares on (§8.4): last edit, else creation. */
    val revision: OffsetDateTime? get() = edited ?: at

    fun withAttrs(update: Map<String, String?>): JournalEntry {
        val next = LinkedHashMap(attrs)
        for ((k, v) in update) if (v == null) next.remove(k) else next[k] = v
        return copy(attrs = next)
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_AT = "at"
        const val KEY_EDITED = "edited"
        const val KEY_LAT = "lat"
        const val KEY_LNG = "lng"
        const val KEY_ACC = "acc"
        const val KEY_PLACE = "place"
        const val KEY_PHOTOS = "photos"
        const val KEY_BY = "by"
        const val BY_MINIS = "minis"

        fun create(id: String, at: OffsetDateTime, body: String, photos: List<String> = emptyList()): JournalEntry {
            val attrs = linkedMapOf(KEY_ID to id, KEY_AT to JournalCodec.formatTime(at))
            if (photos.isNotEmpty()) attrs[KEY_PHOTOS] = photos.joinToString(",")
            return JournalEntry(JournalCodec.headingFor(at), attrs, body)
        }
    }
}

/** An entry plus the exact character range it occupies in the source text. */
data class JournalBlock(val entry: JournalEntry, val start: Int, val end: Int)

/**
 * A parsed day file. [text] is the untouched source; [headEnd] is where the
 * first entry starts (front matter + any free-form preamble live before it).
 */
class JournalDocument(val text: String, val headEnd: Int, val blocks: List<JournalBlock>) {
    val head: String get() = text.substring(0, headEnd)
    val entries: List<JournalEntry> get() = blocks.map { it.entry }

    /** `date:` from the front matter, if present. */
    val frontMatterDate: String?
        get() {
            val m = FRONT_MATTER.find(text) ?: return null
            return m.groupValues[1].lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("date:") }
                ?.removePrefix("date:")?.trim()
        }

    /**
     * Markers that look like entry metadata but did not parse as one — a sign
     * the file was damaged by hand. Restore treats such a file as unmergeable.
     */
    val strayMarkerCount: Int
        get() = MARKER.findAll(text).count() - blocks.size

    private companion object {
        val FRONT_MATTER = Regex("\\A---\\r?\\n([\\s\\S]*?)\\r?\\n---")
        val MARKER = Regex("<!--\\s*minis:entry")
    }
}

object JournalCodec {
    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    private val HEADING_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val META_LINE = Regex("^<!--\\s*minis:entry\\b(.*?)\\s*-->\\s*$")
    private val ATTR = Regex("([A-Za-z_][A-Za-z0-9_-]*)=(?:\"([^\"]*)\"|(\\S+))")
    private const val BODY_MARKER = "<!-- minis:entry"
    private const val BODY_MARKER_ESCAPED = "&lt;!-- minis:entry"

    fun formatTime(t: OffsetDateTime): String = TIME_FMT.format(t)
    fun parseTime(s: String): OffsetDateTime? = runCatching { OffsetDateTime.parse(s.trim()) }.getOrNull()
    fun headingFor(t: OffsetDateTime): String = HEADING_FMT.format(t)

    fun frontMatter(date: LocalDate): String = "---\ndate: $date\n---\n"

    // ── Parsing (§4.3) ────────────────────────────────────────────────────

    fun parse(text: String): JournalDocument {
        // Line starts, so blocks can be addressed by character offset.
        val lineStarts = ArrayList<Int>()
        lineStarts += 0
        for (i in text.indices) if (text[i] == '\n' && i + 1 <= text.length) lineStarts += i + 1
        if (lineStarts.last() == text.length && text.isNotEmpty()) lineStarts.removeAt(lineStarts.size - 1)
        fun line(i: Int): String {
            val s = lineStarts[i]
            val e = if (i + 1 < lineStarts.size) lineStarts[i + 1] - 1 else text.length - (if (text.endsWith('\n')) 1 else 0)
            return text.substring(s, e).removeSuffix("\r")
        }

        // An entry starts at a "## " line whose NEXT line is the metadata comment.
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 1 < lineStarts.size) {
            if (line(i).startsWith("## ") && META_LINE.matches(line(i + 1))) {
                starts += i
                i += 2
            } else {
                i += 1
            }
        }

        val blocks = starts.mapIndexed { n, startLine ->
            val endLine = if (n + 1 < starts.size) starts[n + 1] else lineStarts.size
            val start = lineStarts[startLine]
            val end = if (endLine < lineStarts.size) lineStarts[endLine] else text.length
            val heading = line(startLine).removePrefix("## ").trim()
            val attrs = parseAttrs(META_LINE.find(line(startLine + 1))!!.groupValues[1])
            val bodyLines = (startLine + 2 until endLine).map(::line)
            JournalBlock(JournalEntry(heading, attrs, unescapeBody(trimBlankLines(bodyLines))), start, end)
        }
        val headEnd = blocks.firstOrNull()?.start ?: text.length
        return JournalDocument(text, headEnd, blocks)
    }

    fun parseAttrs(raw: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in ATTR.findAll(raw)) {
            val key = m.groupValues[1]
            val quoted = m.groups[2]
            out[key] = if (quoted != null) unescapeValue(quoted.value) else m.groupValues[3]
        }
        return out
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    fun renderAttrs(attrs: Map<String, String>): String =
        attrs.entries.joinToString(" ") { (k, v) -> "$k=${renderValue(v)}" }

    /** One entry, always ending in exactly one newline. */
    fun renderEntry(e: JournalEntry): String = buildString {
        append("## ").append(e.at?.let(::headingFor) ?: e.heading).append('\n')
        append("<!-- minis:entry ").append(renderAttrs(e.attrs)).append(" -->\n")
        val body = escapeBody(e.body.trimEnd('\n', '\r'))
        if (body.isNotEmpty()) append(body).append('\n')
    }

    private fun renderValue(v: String): String {
        val needsQuotes = v.isEmpty() || v.contains("--") ||
            v.any { it.isWhitespace() || it == ',' || it == '"' || it == '&' || it.code > 0x7E }
        if (!needsQuotes) return v
        val escaped = v.replace("&", "&amp;").replace("\"", "&quot;").replace("--", "&#45;&#45;")
        return "\"$escaped\""
    }

    private fun unescapeValue(v: String): String =
        v.replace("&#45;", "-").replace("&quot;", "\"").replace("&amp;", "&")

    private fun escapeBody(b: String): String = b.replace(BODY_MARKER, BODY_MARKER_ESCAPED)
    private fun unescapeBody(b: String): String = b.replace(BODY_MARKER_ESCAPED, BODY_MARKER)

    private fun trimBlankLines(lines: List<String>): String {
        var s = 0
        var e = lines.size
        while (s < e && lines[s].isBlank()) s++
        while (e > s && lines[e - 1].isBlank()) e--
        return lines.subList(s, e).joinToString("\n")
    }

    // ── Edits (§4.5): touch only the target block ──────────────────────────

    /** Append [entry] to [text] (null/blank = new file for [date]). */
    fun append(text: String?, date: LocalDate, entry: JournalEntry): String {
        val base = if (text.isNullOrBlank()) frontMatter(date) else text
        val sep = when {
            base.endsWith("\n\n") -> ""
            base.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        return base + sep + renderEntry(entry)
    }

    /**
     * Replace the block whose id is [id] with [replacement] (null = delete).
     * Bytes outside that block are left exactly as they were. Returns null when
     * no single block carries that id (missing, or duplicated by hand — §4.5
     * disables editing duplicates rather than guessing which one was meant).
     */
    fun replace(text: String, id: String, replacement: JournalEntry?): String? {
        val doc = parse(text)
        val matches = doc.blocks.filter { it.entry.id == id }
        if (matches.size != 1) return null
        val block = matches.single()
        val old = text.substring(block.start, block.end)
        // Keep the blank-line separator that followed the old block.
        val trailing = old.length - old.trimEnd('\n', '\r').length
        val tail = old.substring(old.length - trailing)
        val newRaw = if (replacement == null) "" else renderEntry(replacement).removeSuffix("\n") + tail.ifEmpty { "\n" }
        return text.substring(0, block.start) + newRaw + text.substring(block.end)
    }
}
