package com.openminis.app.data.journal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** [T-today-habits] habits.md and check-in entries (SPEC §14.2). */
class HabitsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Shanghai")
    private var now = OffsetDateTime.of(2026, 10, 12, 7, 45, 10, 0, ZoneOffset.ofHours(8))
    private fun store() = JournalStore(tmp.root, { zone }, { now })
    private val day = LocalDate.of(2026, 10, 12) // a Monday

    @Test
    fun parsesAndKeepsForeignLines() {
        val text = """
            ---
            type: habits
            ---

            我自己写的说明
            - 俯卧撑 <!-- minis:habit id=A target=20 unit=个 -->
            - 深蹲 <!-- minis:habit id=B target=50 unit=个 days=mon,wed,fri future=x -->
            - 冥想 <!-- minis:habit id=C archived=2026-10-01 -->
        """.trimIndent() + "\n"
        val hs = HabitCodec.parse(text)
        assertEquals(listOf("俯卧撑", "深蹲", "冥想"), hs.map { it.name })
        assertEquals(20, hs[0].target)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), hs[1].days)
        assertTrue(hs[1].dueOn(day))
        assertFalse(hs[1].dueOn(day.plusDays(1)))
        assertFalse(hs[2].dueOn(day))
        assertEquals(1, hs[2].goal)

        val out = HabitCodec.replace(text, "B", hs[1].copy(name = "深蹲（慢）"))!!
        assertTrue(out.contains("我自己写的说明\n"))
        assertTrue("unknown keys survive", out.contains("- 深蹲（慢） <!-- minis:habit id=B target=50 unit=\"个\" days=\"mon,wed,fri\" future=x -->"))
        assertEquals(text.lines().size, out.lines().size)
    }

    @Test
    fun namesCannotBreakTheComment() {
        val h = Habit.create("X", "a --> b\nc", 1, null, null)
        assertEquals("a -> b c", h.name)
        assertEquals("a -> b c", HabitCodec.parse(HabitCodec.render(h)).single().name)
    }

    @Test
    fun addAndUpdateThroughStore() = runBlocking {
        val s = store()
        val h = s.addHabit("俯卧撑", 20, "个", setOf(DayOfWeek.MONDAY))
        s.addHabit("冥想", null, "分钟", null)
        val text = s.habitsFile.readText()
        assertTrue(text.startsWith("---\ntype: habits\n---\n\n- 俯卧撑 <!-- minis:habit id=${h.id} target=20 unit=\"个\" days=mon -->\n"))
        assertTrue("unit dropped without a target", text.contains("- 冥想 <!-- minis:habit id="))
        assertFalse(text.contains("分钟"))
        assertTrue(s.updateHabit(h.id!!) { it.withAttrs(mapOf(Habit.KEY_ARCHIVED to "2026-10-12")) })
        assertTrue(s.readHabits().first().archived)
        assertTrue("habits.md is not a day", s.availableDays().isEmpty())
    }

    @Test
    fun checkInsAccumulateInOneEntry() = runBlocking {
        val s = store()
        val h = s.addHabit("深蹲", 50, "个", null)
        s.setCheckIn(day, h, 30)
        now = now.plusHours(10)
        s.setCheckIn(day, h, 50)
        val e = s.readDay(day)!!.entries.single()
        assertEquals("2026-10-12T07:45:10+08:00", e.attrs["at"])
        assertEquals("50", e.attrs["done"])
        assertEquals(h.id, e.attrs["habit"])
        assertEquals("✅ 深蹲 50/50 个", e.body)
        assertTrue(e.attrs.containsKey("edited"))
        assertEquals(mapOf(h.id!! to 50), CheckIns.totals(s.readDay(day)!!.entries))

        s.setCheckIn(day, h, 0)
        assertTrue(s.readDay(day)!!.entries.isEmpty())
    }

    @Test
    fun duplicateEntriesAreSummedAndShrinkFromTheEnd() = runBlocking {
        val s = store()
        val h = s.addHabit("俯卧撑", 20, "个", null)
        // Two check-in entries for one habit, as after merging two phones' backups.
        val f = s.fileFor(day)
        f.parentFile!!.mkdirs()
        f.writeText(
            "---\ndate: 2026-10-12\n---\n\n" +
                "## 07:00\n<!-- minis:entry id=01AAAAAAAAAAAAAAAAAAAAAAAA at=2026-10-12T07:00:00+08:00 habit=${h.id} done=15 -->\n俯卧撑 15/20 个\n\n" +
                "## 08:00\n<!-- minis:entry id=01BBBBBBBBBBBBBBBBBBBBBBBB at=2026-10-12T08:00:00+08:00 habit=${h.id} done=10 -->\n俯卧撑 10/20 个\n",
        )
        assertEquals(25, CheckIns.totals(s.readDay(day)!!.entries)[h.id])
        s.setCheckIn(day, h, 18)
        val es = s.readDay(day)!!.entries
        assertEquals(listOf(15, 3), es.map(CheckIns::done))
        s.setCheckIn(day, h, 10)
        assertEquals(listOf(10), s.readDay(day)!!.entries.map(CheckIns::done))
    }

    @Test
    fun mergeAppendsOnlyMissingHabits() {
        val local = "- 俯卧撑 <!-- minis:habit id=A target=20 -->\n"
        val incoming = "- 俯卧撑（旧名） <!-- minis:habit id=A target=10 -->\n- 深蹲 <!-- minis:habit id=B -->\n"
        val (text, added) = HabitCodec.merge(local, incoming)!!
        assertEquals(1, added)
        assertEquals("- 俯卧撑 <!-- minis:habit id=A target=20 -->\n- 深蹲 <!-- minis:habit id=B -->\n", text)
        assertNull(HabitCodec.merge(text, incoming))
    }
}
