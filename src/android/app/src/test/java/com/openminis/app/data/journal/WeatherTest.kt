package com.openminis.app.data.journal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** [T-today-weather] Weather in the day file's front matter (SPEC §15). */
class WeatherTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = OffsetDateTime.of(2026, 10, 12, 8, 0, 0, 0, ZoneOffset.ofHours(8))
    private val day = LocalDate.of(2026, 10, 12)
    private val w = DayWeather(61, 18, 23, now)

    @Test
    fun frontMatterIsSetWithoutTouchingEntries() {
        val text = "---\ndate: 2026-10-12\nmood: ok\n---\n\n## 09:32\n<!-- minis:entry id=A at=2026-10-12T09:32:00+08:00 -->\n咖啡\n"
        val out = JournalCodec.withFrontMatter(text, w.frontMatter("小雨"))
        assertTrue(out.startsWith("---\ndate: 2026-10-12\nmood: ok\nweather: 🌧 小雨 18–23°\nweather_code: 61\ntemp_min: 18\ntemp_max: 23\nweather_updated: \"2026-10-12T08:00:00+08:00\"\n---\n"))
        assertTrue("entries byte for byte", out.endsWith(text.substring(text.indexOf("\n---\n") + 5)))
        assertEquals(w, DayWeather.from(JournalCodec.frontMatterValues(out)))

        // Updating replaces in place, no duplicate keys.
        val again = JournalCodec.withFrontMatter(out, DayWeather(3, 17, 22, now).frontMatter("阴"))
        assertEquals(1, Regex("weather_code:").findAll(again).count())
        assertEquals(3, DayWeather.from(JournalCodec.parse(again).frontMatter)!!.code)
        assertEquals(1, JournalCodec.parse(again).entries.size)
    }

    @Test
    fun fileWithoutFrontMatterGetsOne() {
        val out = JournalCodec.withFrontMatter("自由文字\n", mapOf("weather_code" to "0"))
        assertEquals("---\nweather_code: 0\n---\n\n自由文字\n", out)
    }

    @Test
    fun storeCreatesDayFileForWeatherAndAppendsAfterIt() = runBlocking {
        val s = JournalStore(tmp.root, { zone }, { now })
        s.setFrontMatter(day, w.frontMatter("小雨"))
        s.add("早上好")
        val doc = s.readDay(day)!!
        assertEquals(18, DayWeather.from(doc.frontMatter)!!.minC)
        assertEquals(listOf("早上好"), doc.entries.map { it.body })
    }

    @Test
    fun restoreAddsMissingWeatherButKeepsLocal() {
        val entry = "## 09:32\n<!-- minis:entry id=A at=2026-10-12T09:32:00+08:00 -->\n咖啡\n"
        val local = "---\ndate: 2026-10-12\n---\n\n$entry"
        val incoming = JournalCodec.withFrontMatter(local, w.frontMatter("小雨"))
        val merged = (JournalMerge.merge(local, incoming) as JournalMerge.Result.Merged).text
        assertEquals(61, DayWeather.from(JournalCodec.frontMatterValues(merged))!!.code)
        assertEquals(1, JournalCodec.parse(merged).entries.size)

        // Local already has weather: the local record wins, nothing to do.
        val localW = JournalCodec.withFrontMatter(local, DayWeather(0, 10, 20, now).frontMatter("晴"))
        assertTrue(JournalMerge.merge(localW, incoming) is JournalMerge.Result.Unchanged)
    }

    @Test
    fun missingOrPartialRecordIsNull() {
        assertNull(DayWeather.from(mapOf("weather_code" to "61")))
        assertEquals("🌦", TodayWeather.emoji(81))
        assertEquals(com.openminis.app.R.string.weather_light_rain, TodayWeather.label(61))
    }

    @Test
    fun parsesDayZeroOfTheSharedForecast() {
        // Shape of WeatherManager.fetchWeatherJson's response (10-day daily forecast).
        val json = org.json.JSONObject(
            """{"daily":{"time":["2026-10-12","2026-10-13"],"weather_code":[61,3],
               "temperature_2m_max":[22.6,20.1],"temperature_2m_min":[17.5,15.0]}}""",
        )
        assertEquals(DayWeather(61, 18, 23, now), TodayWeather.parseToday(json, now))
        assertNull(TodayWeather.parseToday(org.json.JSONObject("{}"), now))
    }
}
