package com.openminis.app.ui.today

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MurmurRetimeTest {
    private val zone = ZoneId.systemDefault()
    private fun ms(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private fun at(ms: Long) = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone).toString()

    @Test fun keepsTheReminderDay() =
        assertEquals("2026-10-16T08:00", at(retime(ms("2026-10-16T09:00"), 8, 0, nowMs = ms("2026-10-15T20:00"))))

    @Test fun laterToday() =
        assertEquals("2026-10-15T21:00", at(retime(ms("2026-10-15T22:30"), 21, 0, nowMs = ms("2026-10-15T20:00"))))

    @Test fun aTimeAlreadyPastMovesToTomorrow() =
        assertEquals("2026-10-16T19:00", at(retime(ms("2026-10-15T22:30"), 19, 0, nowMs = ms("2026-10-15T20:00"))))

    @Test fun aRungReminderIsSetFromToday() =
        assertEquals("2026-10-15T21:00", at(retime(ms("2026-10-14T09:00"), 21, 0, nowMs = ms("2026-10-15T20:00"))))
}
