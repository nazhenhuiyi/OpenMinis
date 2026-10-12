package com.openminis.app.notification

import com.openminis.app.notification.WeatherAlerts.Kind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** [T-today-weather-alerts] Alert rules (SPEC §16.2) and throttling (§16.4). */
class WeatherAlertsTest {
    private val now = LocalDateTime.of(2026, 10, 12, 13, 20)
    private val today = now.toLocalDate()

    /** hours: (hour, prob, mm, code, gust) */
    private fun json(
        hours: List<List<Number>>,
        nowMm: Double = 0.0,
        nowCode: Int = 1,
        max: List<Double> = listOf(24.0, 23.0),
        min: List<Double> = listOf(15.0, 14.0),
    ) = JSONObject().apply {
        put("current", JSONObject().put("precipitation", nowMm).put("weather_code", nowCode))
        put("hourly", JSONObject().apply {
            put("time", JSONArray(hours.map { "2026-10-12T%02d:00".format(it[0].toInt()) }))
            put("precipitation_probability", JSONArray(hours.map { it[1] }))
            put("precipitation", JSONArray(hours.map { it[2] }))
            put("weather_code", JSONArray(hours.map { it[3] }))
            put("wind_gusts_10m", JSONArray(hours.map { it[4] }))
        })
        put("daily", JSONObject().apply {
            put("time", JSONArray(listOf("2026-10-11", "2026-10-12")))
            put("temperature_2m_max", JSONArray(max))
            put("temperature_2m_min", JSONArray(min))
        })
    }

    @Test
    fun rainAboutToStartOnlyWhileDry() {
        val hours = listOf(listOf(13, 10, 0.0, 2, 10), listOf(14, 80, 1.2, 61, 12), listOf(15, 90, 2.0, 63, 15))
        val a = WeatherAlerts.evaluate(json(hours), now)
        assertEquals(listOf(Kind.RAIN), a.map { it.kind })
        assertEquals(LocalDateTime.of(2026, 10, 12, 14, 0), a.single().at)
        // Already raining: "about to rain" says nothing new.
        assertTrue(WeatherAlerts.evaluate(json(hours, nowMm = 0.6, nowCode = 61), now).isEmpty())
    }

    @Test
    fun beyondTwoHoursIsIgnoredAndStormWins() {
        val later = listOf(listOf(13, 0, 0.0, 1, 5), listOf(16, 95, 5.0, 95, 70))
        assertTrue(WeatherAlerts.evaluate(json(later), now).isEmpty())
        val soon = listOf(listOf(14, 90, 5.0, 95, 20), listOf(15, 90, 6.0, 65, 20))
        assertEquals(listOf(Kind.STORM), WeatherAlerts.evaluate(json(soon), now).map { it.kind })
    }

    @Test
    fun windAndDailyExtremes() {
        val hours = listOf(listOf(14, 0, 0.0, 3, 62.4))
        val a = WeatherAlerts.evaluate(json(hours, max = listOf(30.0, 36.4), min = listOf(20.0, 22.0)), now)
        assertEquals(listOf(Kind.WIND, Kind.HEAT), a.map { it.kind })
        assertEquals(62, a[0].value)
        assertEquals(36, a[1].value)
        val drop = WeatherAlerts.evaluate(json(emptyList(), max = listOf(25.0, 15.0), min = listOf(12.0, -6.0)), now)
        assertEquals(listOf(Kind.COLD, Kind.DROP), drop.map { it.kind })
        assertEquals(10, drop[1].value)
    }

    @Test
    fun throttling() {
        val rain = WeatherAlerts.Alert(Kind.RAIN, now, null)
        val storm = WeatherAlerts.Alert(Kind.STORM, now, null)
        val heat = WeatherAlerts.Alert(Kind.HEAT, null, 36)
        val t = 1_000_000_000L
        val sentRain = mapOf("precipAt" to (t - 60_000).toString(), "precipSeverity" to "1")
        // Same rain within the cooldown: quiet. A storm is an escalation: let through.
        assertTrue(WeatherAlerts.fresh(listOf(rain), sentRain, t, today).isEmpty())
        assertEquals(listOf(storm), WeatherAlerts.fresh(listOf(storm), sentRain, t, today))
        assertEquals(listOf(rain), WeatherAlerts.fresh(listOf(rain), sentRain, t + 5 * 3_600_000L, today))
        // Daily kinds once a day; the daily cap stops everything.
        assertTrue(WeatherAlerts.fresh(listOf(heat), mapOf("day:HEAT" to today.toString()), t, today).isEmpty())
        assertEquals(listOf(heat), WeatherAlerts.fresh(listOf(heat), mapOf("day:HEAT" to LocalDate.of(2026, 10, 11).toString()), t, today))
        assertTrue(WeatherAlerts.fresh(listOf(storm), mapOf("countDay" to today.toString(), "count" to "3"), t, today).isEmpty())
    }

    @Test
    fun activeAlertsRoundTripAsData() {
        val alerts = listOf(
            WeatherAlerts.Alert(Kind.STORM, LocalDateTime.of(2026, 10, 12, 15, 0), null),
            WeatherAlerts.Alert(Kind.WIND, LocalDateTime.of(2026, 10, 12, 16, 0), 62),
            WeatherAlerts.Alert(Kind.HEAT, null, 36),
        )
        assertEquals(alerts, WeatherAlerts.decode(WeatherAlerts.encode(alerts)))
        assertTrue(WeatherAlerts.decode("NOPE||;garbage").isEmpty())
    }
}
