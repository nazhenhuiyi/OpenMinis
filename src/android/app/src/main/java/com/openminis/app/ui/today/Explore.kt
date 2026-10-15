package com.openminis.app.ui.today

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

// [T-today-explore] 探索 (SPEC §20): the author's own days, handed back in
// ways they did not expect — an old page, their firsts, small findings. All
// from the journal on the phone; nothing leaves it.

/** One note as 探索 sees it: words, photos and where. */
data class ExploreNote(
    val id: String,
    val date: LocalDate,
    val atMs: Long,
    val body: String,
    val photos: List<String>,
    val place: String?,
)

/** A page turned up for today: from this day [monthsAgo] months back, or (null) at random. */
data class ExplorePage(val note: ExploreNote, val monthsAgo: Int?)

/**
 * A first time: a kind first seen, or a dish first made. [key] opens it
 * (latin name / dish); [photo] is a relative path from that first time,
 * [noteId] the murmur it was written in (dishes: 我的菜单 scrolls to it).
 */
data class ExploreFirst(
    val species: Boolean,
    val name: String,
    val atMs: Long,
    val key: String,
    val photo: String? = null,
    val noteId: String? = null,
)

enum class TimeOfDay { DAWN, MORNING, AFTERNOON, EVENING, NIGHT }

data class ExploreFacts(
    val notes: Int,
    val since: LocalDate,
    /** Characters written, spaces not counted. */
    val chars: Int,
    val longestStreak: Int,
    val favouriteTime: TimeOfDay,
    val favouriteTimePercent: Int,
    val busiestDay: LocalDate,
    val busiestCount: Int,
    val photos: Int,
)

object Explore {
    /** How many pages a day: something to look forward to, not a feed. */
    const val PAGES_A_DAY = 3

    /** Looked back to on the day, nearest-to-a-year first. */
    private val BACK = listOf(48, 36, 24, 12, 6, 3, 2, 1)

    /** A random page is at least this old: last week is not a surprise. */
    private const val MIN_AGE_DAYS = 14L

    /**
     * Today's pages, in order: the same day N months back (the best note of
     * that day), then old notes at random — the same picks all day, new ones
     * tomorrow. Notes with photos or more words come up more often.
     */
    fun pages(notes: List<ExploreNote>, today: LocalDate, n: Int = PAGES_A_DAY): List<ExplorePage> {
        val byDate = notes.groupBy { it.date }
        val out = ArrayList<ExplorePage>()
        for (m in BACK) {
            val d = today.minusMonths(m.toLong())
            // The 31st has no "a month ago" in a 30-day month: skip rather than show the 30th twice.
            if (d.dayOfMonth != today.dayOfMonth) continue
            val best = byDate[d].orEmpty().filter(::worthShowing).maxByOrNull(::weight) ?: continue
            out += ExplorePage(best, m)
        }
        val taken = out.map { it.note.id }.toSet()
        val eligible = notes.filter { it.id !in taken && worthShowing(it) }
        val pool = eligible.filter { it.date.isBefore(today.minusDays(MIN_AGE_DAYS)) }
            .ifEmpty { eligible.filter { it.date.isBefore(today) } }
            .toMutableList()
        val rnd = Random(today.toEpochDay())
        while (out.size < n && pool.isNotEmpty()) {
            var r = rnd.nextInt(pool.sumOf(::weight))
            val i = pool.indexOfFirst { r -= weight(it); r < 0 }
            out += ExplorePage(pool.removeAt(i), null)
        }
        return out.take(n)
    }

    private fun worthShowing(n: ExploreNote) = n.photos.isNotEmpty() || n.body.count { !it.isWhitespace() } >= 8

    private fun weight(n: ExploreNote): Int =
        1 + (if (n.photos.isNotEmpty()) 2 else 0) + (if (n.body.length >= 40) 1 else 0)

    fun facts(notes: List<ExploreNote>, zone: ZoneId): ExploreFacts? {
        if (notes.isEmpty()) return null
        val dates = notes.map { it.date }.toSortedSet()
        var longest = 1
        var run = 1
        dates.zipWithNext { a, b ->
            run = if (b == a.plusDays(1)) run + 1 else 1
            longest = maxOf(longest, run)
        }
        val times = notes.groupingBy { timeOf(Instant.ofEpochMilli(it.atMs).atZone(zone).hour) }.eachCount()
        val (fav, favN) = times.maxBy { it.value }
        val busiest = notes.groupingBy { it.date }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<LocalDate, Int>> { it.value }.thenByDescending { it.key }).first()
        return ExploreFacts(
            notes = notes.size,
            since = dates.first(),
            chars = notes.sumOf { n -> n.body.count { !it.isWhitespace() } },
            longestStreak = longest,
            favouriteTime = fav,
            favouriteTimePercent = (favN * 100 + notes.size / 2) / notes.size,
            busiestDay = busiest.key,
            busiestCount = busiest.value,
            photos = notes.sumOf { it.photos.size },
        )
    }

    fun timeOf(hour: Int): TimeOfDay = when (hour) {
        in 5..7 -> TimeOfDay.DAWN
        in 8..11 -> TimeOfDay.MORNING
        in 12..16 -> TimeOfDay.AFTERNOON
        in 17..19 -> TimeOfDay.EVENING
        else -> TimeOfDay.NIGHT
    }

    /**
     * Each kind's first sighting and each dish's first making, newest first —
     * at most [perKind] of each, so a busy month of plants does not hide the
     * kitchen.
     */
    fun firsts(species: List<CollectedSpecies>, food: List<FoodNote>, perKind: Int = 4): List<ExploreFirst> {
        val made = HashMap<String, FoodNote>()
        for (f in food) for ((dish, mine) in f.dishes) {
            if (mine && f.atMs < (made[dish]?.atMs ?: Long.MAX_VALUE)) made[dish] = f
        }
        val kinds = species.map { ExploreFirst(true, it.name, it.first.atMs, it.latin, it.first.photos.firstOrNull()?.relativePath) }
            .sortedByDescending { it.atMs }.take(perKind)
        val dishes = made.map { (dish, f) -> ExploreFirst(false, dish, f.atMs, dish, f.photos.firstOrNull(), f.id) }
            .sortedByDescending { it.atMs }.take(perKind)
        return (kinds + dishes).sortedByDescending { it.atMs }
    }
}

/** Everything the 探索 tab shows, built in one go (see TodayViewModel.refreshExplore). */
data class ExploreData(
    val today: LocalDate,
    val pages: List<ExplorePage>,
    val facts: ExploreFacts?,
    val firsts: List<ExploreFirst>,
    val speciesCount: Int,
    val natureCover: com.openminis.app.data.photos.CameraPhoto?,
    /** 吃喝 needs the 碎碎念 model to have read the murmurs (§19.6). */
    val showFood: Boolean,
    /** The food murmurs, newest first: 我的菜单 opens on these at once. */
    val food: List<FoodNote>,
    val foodCover: com.openminis.app.data.photos.CameraPhoto?,
    /** The pages' photos, by relative path. */
    val photos: Map<String, com.openminis.app.data.photos.CameraPhoto>,
)
