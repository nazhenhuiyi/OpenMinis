package com.openminis.app.notification

import com.openminis.app.data.journal.CheckIns
import com.openminis.app.data.journal.Habit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** [T-today-habit-reminder] When the evening reminder runs and what it lists (SPEC §14.6). */
class HabitReminderTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone)
    private val eight = 20 * 60

    @Test
    fun runsTodayAtTheTimeUntilItHasRun() {
        assertEquals(at(15, 20), HabitReminder.nextRun(at(15, 9), eight, checkedToday = false))
        assertEquals(at(16, 20), HabitReminder.nextRun(at(15, 9), eight, checkedToday = true))
        assertEquals(at(16, 20), HabitReminder.nextRun(at(15, 20, 30), eight, checkedToday = true))
    }

    @Test
    fun aMissedReminderIsSentLateButNotTooLate() {
        // Phone was off at 20:00 and came back at 21:10: send now.
        assertEquals(at(15, 21, 10), HabitReminder.nextRun(at(15, 21, 10), eight, checkedToday = false))
        // At 22:30 it is too late for today.
        assertEquals(at(16, 20), HabitReminder.nextRun(at(15, 22, 30), eight, checkedToday = false))
    }

    @Test
    fun listsOnlyWhatIsDueAndOpen() {
        val wed = LocalDate.of(2026, 10, 14)
        val pushUps = Habit.create("A", "俯卧撑", 4, "组", null)
        val twist = Habit.create("B", "俄罗斯转体", 5, "组", null)
        val protein = Habit.create("C", "蛋白粉", null, null, null)
        val weekend = Habit.create("D", "长跑", null, null, setOf(DayOfWeek.SATURDAY))
        val gone = Habit.create("E", "跳绳", null, null, null).withAttrs(mapOf(Habit.KEY_ARCHIVED to "2026-10-13"))
        val open = CheckIns.open(listOf(pushUps, twist, protein, weekend, gone), mapOf("A" to 4, "B" to 2), wed)
        assertEquals(listOf("B", "C"), open.map { it.habit.id })
        assertEquals("俄罗斯转体 2/5 组 · 蛋白粉", HabitReminder.body(open))
        assertEquals(emptyList<Any>(), CheckIns.open(listOf(pushUps), mapOf("A" to 4), wed))
    }
}
