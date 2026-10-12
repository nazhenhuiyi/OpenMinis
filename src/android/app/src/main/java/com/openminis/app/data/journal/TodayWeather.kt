package com.openminis.app.data.journal

import com.openminis.app.R
import com.openminis.app.offload.WeatherManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/**
 * [T-today-weather] A day's weather as recorded in the front matter of its
 * journal file (docs/design/today/SPEC.md §15). Recorded once and kept, so a
 * past day shows the weather it actually had — offline, in Obsidian, and to
 * the agent.
 */
data class DayWeather(
    /** WMO weather interpretation code, as Open-Meteo reports it. */
    val code: Int,
    val minC: Int,
    val maxC: Int,
    val updated: OffsetDateTime?,
) {
    /** "🌧 小雨 18–23°" — the day header and the front matter's human line. */
    fun summary(label: String): String = "${TodayWeather.emoji(code)} $label $minC–$maxC°"

    /** The front matter keys; `weather` is the human-readable line, the rest are what the app reads. */
    fun frontMatter(label: String): Map<String, String> = linkedMapOf(
        KEY_WEATHER to summary(label),
        KEY_CODE to code.toString(),
        KEY_MIN to minC.toString(),
        KEY_MAX to maxC.toString(),
        KEY_UPDATED to (updated?.let(JournalCodec::formatTime) ?: ""),
    )

    companion object {
        const val KEY_WEATHER = "weather"
        const val KEY_CODE = "weather_code"
        const val KEY_MIN = "temp_min"
        const val KEY_MAX = "temp_max"
        const val KEY_UPDATED = "weather_updated"

        fun from(frontMatter: Map<String, String>): DayWeather? {
            val code = frontMatter[KEY_CODE]?.toIntOrNull() ?: return null
            val min = frontMatter[KEY_MIN]?.toIntOrNull() ?: return null
            val max = frontMatter[KEY_MAX]?.toIntOrNull() ?: return null
            return DayWeather(code, min, max, frontMatter[KEY_UPDATED]?.let(JournalCodec::parseTime))
        }
    }
}

object TodayWeather {
    /** Today's record is refreshed at most this often; past days are never touched. */
    const val REFRESH_MS = 3 * 60 * 60 * 1000L

    /**
     * Today's summary through the shared Open-Meteo client ([WeatherManager],
     * also behind the agent's `android-weather`). Coordinates are rounded to
     * 2 decimals (~1 km) before they leave the phone. Null on any failure.
     */
    suspend fun fetch(lat: Double, lng: Double, now: OffsetDateTime): DayWeather? = withContext(Dispatchers.IO) {
        val json = WeatherManager.fetchTodayJson(round2(lat), round2(lng)) ?: return@withContext null
        parseToday(json, now)
    }

    /** Day 0 of the daily forecast is today at that place (`timezone=auto`). */
    fun parseToday(json: JSONObject, now: OffsetDateTime): DayWeather? = runCatching {
        val daily = json.getJSONObject("daily")
        DayWeather(
            code = daily.getJSONArray("weather_code").getInt(0),
            minC = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            maxC = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            updated = now,
        )
    }.getOrNull()

    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0

    fun emoji(code: Int): String = when (code) {
        0 -> "☀️"
        1 -> "🌤"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫"
        in 51..67 -> "🌧"
        in 71..77, 85, 86 -> "🌨"
        in 80..82 -> "🌦"
        in 95..99 -> "⛈"
        else -> "🌡"
    }

    /** One word for a WMO code, in the UI language (§15.4). */
    fun label(code: Int): Int = when (code) {
        0 -> R.string.weather_clear
        1 -> R.string.weather_mostly_clear
        2 -> R.string.weather_partly_cloudy
        3 -> R.string.weather_overcast
        45, 48 -> R.string.weather_fog
        51, 53, 55 -> R.string.weather_drizzle
        56, 57, 66, 67 -> R.string.weather_freezing_rain
        61 -> R.string.weather_light_rain
        63 -> R.string.weather_rain
        65 -> R.string.weather_heavy_rain
        71, 73, 75, 77, 85, 86 -> R.string.weather_snow
        80, 81, 82 -> R.string.weather_showers
        95, 96, 99 -> R.string.weather_thunder
        else -> R.string.weather_unknown
    }
}
