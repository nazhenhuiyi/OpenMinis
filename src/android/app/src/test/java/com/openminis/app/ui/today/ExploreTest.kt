package com.openminis.app.ui.today

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ExploreTest {
    private val zone = ZoneOffset.ofHours(8)
    private fun note(id: String, date: LocalDate, hour: Int = 21, body: String = "今天很好，写一点长一些的字", photos: List<String> = emptyList()) =
        ExploreNote(id, date, date.atTime(hour, 0).toInstant(zone).toEpochMilli(), body, photos, null)

    private val today = LocalDate.of(2026, 10, 15)

    @Test fun theSameDayMonthsBackComesFirst() {
        val notes = listOf(
            note("a", LocalDate.of(2026, 7, 15)),
            note("b", LocalDate.of(2026, 9, 15), body = "短"),
            note("c", LocalDate.of(2026, 9, 15), photos = listOf("DCIM/x.jpg")),
            note("d", LocalDate.of(2026, 5, 1)),
            note("e", LocalDate.of(2026, 6, 2)),
        )
        val pages = Explore.pages(notes, today)
        assertEquals(listOf(3, 1, null), pages.map { it.monthsAgo })
        assertEquals(listOf("a", "c"), pages.take(2).map { it.note.id })
    }

    @Test fun theSamePagesAllDayNewOnesTomorrow() {
        val notes = (1..40).map { note("n$it", LocalDate.of(2026, 5, 1).plusDays(it.toLong())) }
        assertEquals(Explore.pages(notes, today), Explore.pages(notes, today))
        assertTrue(Explore.pages(notes, today) != Explore.pages(notes, today.plusDays(1)))
        assertEquals(Explore.PAGES_A_DAY, Explore.pages(notes, today).size)
    }

    @Test fun lastWeekIsNoSurpriseUnlessThatIsAllThereIs() {
        val recent = listOf(note("r", today.minusDays(3)))
        assertEquals(listOf("r"), Explore.pages(recent, today).map { it.note.id })
        val mixed = recent + note("old", today.minusDays(40))
        assertEquals(listOf("old"), Explore.pages(mixed, today).map { it.note.id })
    }

    @Test fun findings() {
        val d = LocalDate.of(2026, 10, 1)
        val f = Explore.facts(
            listOf(
                note("1", d, 22, body = "一二 三"),
                note("2", d.plusDays(1), 23, photos = listOf("p")),
                note("3", d.plusDays(1), 9),
                note("4", d.plusDays(2), 21),
                note("5", d.plusDays(5), 21),
            ),
            zone,
        )!!
        assertEquals(3, f.longestStreak)
        assertEquals(TimeOfDay.NIGHT, f.favouriteTime)
        assertEquals(80, f.favouriteTimePercent)
        assertEquals(d.plusDays(1), f.busiestDay)
        assertEquals(2, f.busiestCount)
        assertEquals(1, f.photos)
        assertEquals(d, f.since)
    }

    @Test fun firstsKeepRoomForTheKitchen() {
        val d = LocalDate.of(2026, 10, 1)
        fun ms(day: Long) = d.plusDays(day).atStartOfDay().toInstant(zone).toEpochMilli()
        val species = (1..6).map { i ->
            CollectedSpecies("L$i", "种$i", null, null, i, listOf(SpeciesRecord(ms(10L + i), "", null, emptyList())))
        }
        val food = listOf(
            FoodNote("f1", d, ms(0), "做了宫保鸡丁", listOf("DCIM/a.jpg"), listOf("宫保鸡丁")),
            FoodNote("f2", d, ms(1), "又做了宫保鸡丁", emptyList(), listOf("宫保鸡丁")),
            FoodNote("f3", d, ms(2), "吃了牛肉面", emptyList(), listOf("牛肉面")),
        )
        val f = Explore.firsts(species, food)
        // Each dish once, from the first murmur naming it.
        assertEquals(listOf("种6", "种5", "种4", "种3", "牛肉面", "宫保鸡丁"), f.map { it.name })
        assertEquals("DCIM/a.jpg", f.last().photo)
        assertEquals("f1", f.last().noteId)
    }

    @Test fun wishesAreWhatYouWouldLikeToDo() {
        val d = LocalDate.of(2026, 9, 1)
        fun w(body: String) = Explore.wishIn(note("x", d, body = body))?.text
        assertEquals("看完纪录片，好想去莫高窟看看", w("看完纪录片，好想去莫高窟看看。下次吧"))
        assertEquals("买了一套器具，其实很想学做手冲咖啡呢", w("买了一套器具，其实很想学做手冲咖啡呢～"))
        assertEquals("有机会试试", w("读到一篇讲口蘑的文章。有机会试试"))
        // Musing, not wishing.
        assertEquals(null, w("想做点什么才好呢，一时没主意"))
        // Already done.
        assertEquals(null, w("想喝粥，于是煮了一锅"))
        assertEquals(null, w("今天的晚霞好好看"))
    }

    @Test fun wishesNewestFirstAndTheirFate() {
        val a = note("a", LocalDate.of(2026, 8, 1), body = "想去动物园")
        val b = note("b", LocalDate.of(2026, 9, 1), body = "想学做咖啡").copy(wish = Explore.WISH_DONE)
        val ws = Explore.wishes(listOf(a, b))
        assertEquals(listOf("b", "a"), ws.map { it.note.id })
        assertTrue(ws[0].done && !ws[0].open)
        assertTrue(ws[1].open)
    }
}
