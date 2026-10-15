package com.openminis.app.data.journal

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * [T-today-habits] One daily check-in item ("20 个俯卧撑"), defined by the user
 * in `/var/minis/journal/habits.md` (docs/design/today/SPEC.md §14.2).
 *
 * [attrs] keeps every metadata key in file order, unknown ones included, so a
 * rewrite never drops fields a newer version added (same rule as §4.4).
 */
data class Habit(
    val name: String,
    val attrs: LinkedHashMap<String, String>,
) {
    // Parsed once: Habit is immutable and these are read on every recomposition.
    val id: String? = attrs[KEY_ID]?.takeIf { it.isNotBlank() }

    /** Daily goal; null = a plain tick-box habit. */
    val target: Int? = attrs[KEY_TARGET]?.toIntOrNull()?.takeIf { it > 0 }
    val unit: String? = attrs[KEY_UNIT]?.takeIf { it.isNotBlank() }
    val archived: Boolean = attrs[KEY_ARCHIVED] != null

    /** Days it is due; null = every day. An unparseable value also means every day. */
    val days: Set<DayOfWeek>? =
        attrs[KEY_DAYS]?.split(',')?.mapNotNull { DAY_CODES[it.trim().lowercase()] }?.toSet()?.takeIf { it.isNotEmpty() }

    /** What one "done" means: the target for counted habits, 1 for tick-boxes. */
    val goal: Int get() = target ?: 1

    /** "20 个", or null for a tick-box habit. */
    val goalText: String? get() = target?.let { "$it" + (unit?.let { u -> " $u" } ?: "") }

    /** "30/50 个", or null for a tick-box habit — the one place this is formatted. */
    fun progressText(done: Int): String? = target?.let { "$done/$it" + (unit?.let { u -> " $u" } ?: "") }

    fun dueOn(date: LocalDate): Boolean = !archived && (days?.contains(date.dayOfWeek) ?: true)

    fun withAttrs(update: Map<String, String?>): Habit = copy(attrs = attrs.withUpdates(update))

    /**
     * The user's definition, normalised in one place: a blank or 0 target means
     * tick-box, a unit only goes with a target, all 7 days means every day.
     */
    fun withDefinition(name: String, target: Int?, unit: String?, days: Set<DayOfWeek>?): Habit {
        val t = target?.takeIf { it > 0 }
        return copy(name = HabitCodec.cleanName(name)).withAttrs(
            mapOf(
                KEY_TARGET to t?.toString(),
                KEY_UNIT to unit?.trim()?.takeIf { it.isNotEmpty() && t != null },
                KEY_DAYS to daysAttr(days),
            ),
        )
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_TARGET = "target"
        const val KEY_UNIT = "unit"
        const val KEY_DAYS = "days"
        const val KEY_ARCHIVED = "archived"

        val DAY_CODES: Map<String, DayOfWeek> = linkedMapOf(
            "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
            "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY,
            "sun" to DayOfWeek.SUNDAY,
        )

        fun daysAttr(days: Set<DayOfWeek>?): String? =
            if (days == null || days.isEmpty() || days.size == 7) null
            else DAY_CODES.filterValues { it in days }.keys.joinToString(",")

        fun create(id: String, name: String, target: Int?, unit: String?, days: Set<DayOfWeek>?): Habit =
            Habit(name, linkedMapOf(KEY_ID to id)).withDefinition(name, target, unit, days)
    }
}

/**
 * [T-today-habits] Reads and edits `habits.md`. Like [JournalCodec], every
 * edit touches only the target line; lines that are not habits stay byte for byte.
 */
object HabitCodec {
    const val FILE_NAME = "habits.md"
    private val LINE = Regex("^- (.*?)\\s*<!--\\s*minis:habit\\b(.*?)\\s*-->\\s*$")
    private const val HEADER = "---\ntype: habits\n---\n\n"

    /** Habits in file order. Lines that do not match are skipped (and kept on rewrite). */
    fun parse(text: String): List<Habit> = text.lineSequence()
        .mapNotNull { LINE.find(it.removeSuffix("\r")) }
        .map { m -> Habit(m.groupValues[1].trim(), JournalCodec.parseAttrs(m.groupValues[2])) }
        .toList()

    fun render(h: Habit): String = "- ${cleanName(h.name)} <!-- minis:habit ${JournalCodec.renderAttrs(h.attrs)} -->"

    /** One line, and nothing that could open or close the metadata comment. */
    fun cleanName(name: String): String =
        name.replace(Regex("\\s+"), " ").replace("<!--", "<!-").replace("-->", "->").trim()

    fun append(text: String?, habit: Habit): String {
        val base = if (text.isNullOrBlank()) HEADER else text
        val sep = if (base.endsWith("\n")) "" else "\n"
        return base + sep + render(habit) + "\n"
    }

    /** Replace the single line carrying [id]; null when missing or duplicated. */
    fun replace(text: String, id: String, replacement: Habit): String? {
        val lines = text.split("\n")
        val hits = lines.indices.filter { i ->
            LINE.find(lines[i].removeSuffix("\r"))?.let { JournalCodec.parseAttrs(it.groupValues[2])[Habit.KEY_ID] == id } == true
        }
        if (hits.size != 1) return null
        val i = hits.single()
        val cr = if (lines[i].endsWith("\r")) "\r" else ""
        return lines.toMutableList().also { it[i] = render(replacement) + cr }.joinToString("\n")
    }

    /**
     * Restore merge (§14.5): habits only in [incoming] are appended to [local];
     * ones both sides have keep the local line. Null = nothing to add.
     */
    fun merge(local: String?, incoming: String): Pair<String, Int>? {
        val have = local?.let(::parse)?.mapNotNull { it.id }?.toSet().orEmpty()
        val missing = parse(incoming).filter { it.id != null && it.id !in have }
        if (missing.isEmpty()) return null
        return missing.fold(local) { acc, h -> append(acc, h) }!! to missing.size
    }
}

/**
 * [T-today-habits] A day's total for one habit, summed over its entries
 * (§14.2). The one model for both the top-bar sheet and the timeline block.
 * [editable] only for today's — past days are read-only (§14.1).
 */
data class HabitProgress(val habit: Habit, val done: Int, val editable: Boolean = true) {
    val complete: Boolean get() = done >= habit.goal
    val fraction: Float get() = (done.toFloat() / habit.goal).coerceIn(0f, 1f)
    val progressText: String? get() = habit.progressText(done)
}

/** Check-in attributes on a journal entry (§14.2). */
object CheckIns {
    const val KEY_HABIT = "habit"
    const val KEY_DONE = "done"

    fun habitId(e: JournalEntry): String? = e.attrs[KEY_HABIT]?.takeIf { it.isNotBlank() }
    fun done(e: JournalEntry): Int = e.attrs[KEY_DONE]?.toIntOrNull()?.coerceAtLeast(0) ?: 1
    fun isCheckIn(e: JournalEntry): Boolean = habitId(e) != null

    /** The human-readable body; the program reads only the attributes. */
    fun body(habit: Habit, done: Int): String =
        (if (done >= habit.goal) "✅ " else "") + listOfNotNull(habit.name, habit.progressText(done)).joinToString(" ")

    /**
     * What is still open on [date]: habits due that day and not yet at their
     * goal, in habits.md order. The one rule behind the evening reminder (§14.6)
     * and the weather alert's outdoor hint (§16).
     */
    fun open(habits: List<Habit>, totals: Map<String, Int>, date: LocalDate): List<HabitProgress> =
        habits.filter { it.id != null && it.dueOn(date) }
            .map { HabitProgress(it, totals[it.id] ?: 0) }
            .filterNot { it.complete }

    /** Totals per habit id for one day's entries. */
    fun totals(entries: List<JournalEntry>): Map<String, Int> =
        entries.filter(::isCheckIn).groupBy { habitId(it)!! }.mapValues { (_, es) -> es.sumOf(::done) }
}
