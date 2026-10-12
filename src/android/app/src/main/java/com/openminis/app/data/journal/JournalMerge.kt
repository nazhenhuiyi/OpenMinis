package com.openminis.app.data.journal

/**
 * [T-today-tab] Restore-time merge of one day file (docs/design/today/SPEC.md §8.4).
 *
 * The backup importer's other file categories overwrite whole files. That is
 * wrong for the journal: restoring an old package on a phone that already has
 * today's entries would silently erase them. Instead the two files are merged
 * entry by entry, keyed on `id`, and nothing the user wrote on either side is
 * dropped.
 */
object JournalMerge {

    sealed interface Result {
        /** Write [text]; [added] new entries, [updated] replaced by a newer revision. */
        data class Merged(val text: String, val added: Int, val updated: Int) : Result

        /** Local file already contains everything in the package; leave it untouched. */
        data object Unchanged : Result

        /**
         * The incoming file looks damaged (entry markers that do not parse).
         * The caller saves it verbatim next to the local file instead of merging.
         */
        data object Unmergeable : Result
    }

    fun merge(local: String?, incoming: String): Result {
        val inDoc = JournalCodec.parse(incoming)
        if (inDoc.strayMarkerCount > 0) return Result.Unmergeable
        if (local.isNullOrBlank()) {
            return if (incoming.isBlank()) Result.Unchanged
            else Result.Merged(incoming, added = inDoc.blocks.size, updated = 0)
        }
        val localDoc = JournalCodec.parse(local)

        // Raw text per block, in local order; replaced/added in place below.
        data class Slot(val entry: JournalEntry, val raw: String)
        val slots = localDoc.blocks.map { Slot(it.entry, local.substring(it.start, it.end)) }.toMutableList()
        val byId = HashMap<String, Int>()
        slots.forEachIndexed { i, s -> s.entry.id?.let { byId.putIfAbsent(it, i) } }
        val localRaw = slots.map { it.raw.trim() }.toHashSet()

        var added = 0
        var updated = 0
        for (block in inDoc.blocks) {
            val raw = incoming.substring(block.start, block.end)
            val id = block.entry.id
            val at = id?.let(byId::get)
            when {
                // Entries without an id cannot be matched; keep them unless an
                // identical block is already there.
                id == null -> if (raw.trim() !in localRaw) { slots += Slot(block.entry, raw); added++ }
                at == null -> { slots += Slot(block.entry, raw); byId[id] = slots.lastIndex; added++ }
                isNewer(block.entry, slots[at].entry) -> { slots[at] = Slot(block.entry, raw); updated++ }
            }
        }
        // Front matter keys only the package has (e.g. a day's weather, §15) are
        // added; keys both sides have keep the local value.
        val localFm = JournalCodec.frontMatterValues(local)
        val missingFm = JournalCodec.frontMatterValues(incoming).filterKeys { it !in localFm }
        if (added == 0 && updated == 0 && missingFm.isEmpty()) return Result.Unchanged

        // Stable sort by creation time; entries with no readable time keep
        // their relative position at the end.
        val ordered = slots.withIndex().sortedWith(
            compareBy<IndexedValue<Slot>>({ it.value.entry.at == null }, { it.value.entry.at?.toInstant() }, { it.index }),
        ).map { it.value }

        val head = localDoc.head.ifBlank { inDoc.head }.let { h ->
            if (missingFm.isEmpty()) h else JournalCodec.withFrontMatter(h, missingFm)
        }
        val sb = StringBuilder(head.trimEnd('\n', '\r'))
        if (sb.isNotEmpty()) sb.append('\n')
        for (s in ordered) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(s.raw.trimEnd('\n', '\r')).append('\n')
        }
        return Result.Merged(sb.toString(), added, updated)
    }

    /** Ties keep the local copy (§8.4). */
    private fun isNewer(incoming: JournalEntry, local: JournalEntry): Boolean {
        val a = incoming.revision?.toInstant() ?: return false
        val b = local.revision?.toInstant() ?: return true
        return a.isAfter(b)
    }
}
