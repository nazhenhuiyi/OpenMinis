package com.openminis.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.openminis.app.R
import com.openminis.app.data.journal.CheckIns
import com.openminis.app.data.journal.JournalStore
import com.openminis.app.data.journal.TodayWeather
import com.openminis.app.offload.ScheduledNotificationReceiver
import com.openminis.app.offload.WeatherManager
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * [T-today-weather-alerts] Hourly check for weather that should change what the
 * user does in the next two hours — rain about to start, storms, heavy
 * rain/snow, strong wind — plus the day's heat, cold or a sudden drop
 * (docs/design/today/SPEC.md §16). Rules and wording are fixed templates: no
 * model, works offline from the last saved position, never asks for background
 * location.
 */
object WeatherAlerts {
    private const val TAG = "WeatherAlerts"
    private const val PREFS = "weather_alerts"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"
    /** The alerts that apply now, as data ([encode]); worded when shown, in the current language. */
    private const val KEY_ACTIVE = "active"
    private const val KEY_ACTIVE_UNTIL = "activeUntil"
    /** [key] of the alerts the user closed in the app. */
    private const val KEY_DISMISSED = "dismissedKey"
    private const val KEY_COUNT_DAY = "countDay"
    private const val KEY_COUNT = "count"
    private const val CHANNEL_ID = "minis_weather_alerts"
    private const val NOTIFICATION_ID = 0x5EA7
    private const val REQUEST_CODE = 0x5EA7

    /** Notifications only between these local hours (§16.4). */
    private const val DAY_START = 7
    private const val DAY_END = 22
    private const val MAX_PER_DAY = 3
    /** An hourly-kind alert is not repeated within this window; precipitation may still escalate. */
    private const val COOLDOWN_MS = 4 * 60 * 60 * 1000L

    // ── Rules (pure, unit-tested) ─────────────────────────────────────────

    enum class Kind(val precip: Boolean, val daily: Boolean, val severity: Int) {
        STORM(true, false, 3), HEAVY(true, false, 2), RAIN(true, false, 1),
        WIND(false, false, 1), HEAT(false, true, 1), COLD(false, true, 1), DROP(false, true, 1),
    }

    /** [at] = local hour it starts (hourly kinds); [value] = gust km/h, °C or the drop. */
    data class Alert(val kind: Kind, val at: LocalDateTime?, val value: Int?)

    private val WET = (51..67) + (71..77) + (80..86) + (95..99)
    private val HEAVY_CODES = setOf(65, 67, 75, 82, 86)

    /** What [json] (from [WeatherManager.fetchAlertJson]) warns about, most severe precipitation first. */
    fun evaluate(json: JSONObject, now: LocalDateTime): List<Alert> {
        val out = ArrayList<Alert>()
        val hourly = json.optJSONObject("hourly")
        if (hourly != null) {
            val times = hourly.getJSONArray("time")
            val prob = hourly.getJSONArray("precipitation_probability")
            val mm = hourly.getJSONArray("precipitation")
            val code = hourly.getJSONArray("weather_code")
            val gust = hourly.getJSONArray("wind_gusts_10m")
            data class Hour(val at: LocalDateTime, val prob: Int, val mm: Double, val code: Int, val gust: Double)
            // The next two hours, counting the one already under way.
            val hours = (0 until times.length()).map { i ->
                Hour(LocalDateTime.parse(times.getString(i)), prob.optInt(i, 0), mm.optDouble(i, 0.0), code.optInt(i, 0), gust.optDouble(i, 0.0))
            }.filter { it.at.plusHours(1).isAfter(now) && !it.at.isAfter(now.plusHours(2)) }

            val current = json.optJSONObject("current")
            val wetNow = current != null && (current.optDouble("precipitation", 0.0) >= 0.1 || current.optInt("weather_code") in WET)
            val storm = hours.firstOrNull { it.code in 95..99 }
            val heavy = hours.firstOrNull { it.code in HEAVY_CODES || it.mm >= 4.0 }
            val rain = hours.firstOrNull { it.prob >= 70 && it.mm >= 0.5 }
            when {
                storm != null -> out += Alert(Kind.STORM, storm.at, null)
                heavy != null -> out += Alert(Kind.HEAVY, heavy.at, null)
                // "About to rain" only means something while it is still dry.
                rain != null && !wetNow -> out += Alert(Kind.RAIN, rain.at, null)
            }
            hours.firstOrNull { it.gust >= 50 }?.let { out += Alert(Kind.WIND, it.at, it.gust.roundToInt()) }
        }
        val daily = json.optJSONObject("daily")
        if (daily != null) {
            val days = daily.getJSONArray("time")
            val todayIdx = (0 until days.length()).firstOrNull { days.getString(it) == now.toLocalDate().toString() }
            if (todayIdx != null) {
                val max = daily.getJSONArray("temperature_2m_max").optDouble(todayIdx)
                val min = daily.getJSONArray("temperature_2m_min").optDouble(todayIdx)
                if (!max.isNaN() && max >= 35) out += Alert(Kind.HEAT, null, max.roundToInt())
                if (!min.isNaN() && min <= -5) out += Alert(Kind.COLD, null, min.roundToInt())
                if (todayIdx > 0 && !max.isNaN()) {
                    val drop = daily.getJSONArray("temperature_2m_max").optDouble(todayIdx - 1) - max
                    if (!drop.isNaN() && drop >= 8) out += Alert(Kind.DROP, null, drop.roundToInt())
                }
            }
        }
        return out
    }

    /**
     * Which of [alerts] are new enough to notify (§16.4): daily kinds once a
     * day; precipitation once per [COOLDOWN_MS] unless it escalates;
     * wind once per cooldown; at most [MAX_PER_DAY] notifications a day.
     */
    fun fresh(alerts: List<Alert>, sent: Map<String, String>, nowMs: Long, today: LocalDate): List<Alert> {
        if (sentToday(sent, today) >= MAX_PER_DAY) return emptyList()
        return alerts.filter { a ->
            when {
                a.kind.daily -> sent["day:${a.kind}"] != today.toString()
                a.kind.precip -> {
                    val lastMs = sent["precipAt"]?.toLongOrNull() ?: 0L
                    val lastSeverity = sent["precipSeverity"]?.toIntOrNull() ?: 0
                    nowMs - lastMs >= COOLDOWN_MS || a.kind.severity > lastSeverity
                }
                else -> nowMs - (sent["at:${a.kind}"]?.toLongOrNull() ?: 0L) >= COOLDOWN_MS
            }
        }
    }

    private fun sentToday(sent: Map<String, String>, today: LocalDate): Int =
        if (sent[KEY_COUNT_DAY] == today.toString()) sent[KEY_COUNT]?.toIntOrNull() ?: 0 else 0

    /** Fixed set of throttle keys (no per-day keys, so the prefs file does not grow). */
    private val SENT_KEYS = listOf(KEY_COUNT_DAY, KEY_COUNT, "precipAt", "precipSeverity") +
        Kind.entries.flatMap { listOf("day:$it", "at:$it") }

    /** Identity of an alert set for dismissal: which kinds, not their wording, times or values. */
    private fun key(alerts: List<Alert>): String = alerts.map { it.kind }.sorted().joinToString(",")

    /** "RAIN|2026-10-12T15:00|;HEAT||36" */
    fun encode(alerts: List<Alert>): String =
        alerts.joinToString(";") { "${it.kind}|${it.at ?: ""}|${it.value ?: ""}" }

    fun decode(s: String): List<Alert> = s.split(";").mapNotNull { part ->
        val f = part.split("|")
        if (f.size != 3) return@mapNotNull null
        val kind = Kind.entries.firstOrNull { it.name == f[0] } ?: return@mapNotNull null
        Alert(kind, f[1].takeIf { it.isNotEmpty() }?.let(LocalDateTime::parse), f[2].toIntOrNull())
    }

    private val OUTDOOR = listOf("跑", "步", "骑", "球", "爬山", "徒步", "遛", "run", "walk", "bike", "cycl", "hike", "jog")

    /** One line per alert, in the UI language, plus a hint about an outdoor check-in still open today. */
    fun text(context: Context, alerts: List<Alert>, openHabits: List<String>): String {
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        val lines = alerts.map { a ->
            val t = a.at?.let(hm::format).orEmpty()
            when (a.kind) {
                Kind.STORM -> context.getString(R.string.weather_alert_storm, t)
                Kind.HEAVY -> context.getString(R.string.weather_alert_heavy, t)
                Kind.RAIN -> context.getString(R.string.weather_alert_rain, t)
                Kind.WIND -> context.getString(R.string.weather_alert_wind, t, a.value ?: 0)
                Kind.HEAT -> context.getString(R.string.weather_alert_heat, a.value ?: 0)
                Kind.COLD -> context.getString(R.string.weather_alert_cold, a.value ?: 0)
                Kind.DROP -> context.getString(R.string.weather_alert_drop, a.value ?: 0)
            }
        }.toMutableList()
        val outdoorWeather = alerts.any { it.kind != Kind.COLD && it.kind != Kind.DROP }
        val habit = openHabits.firstOrNull { name -> OUTDOOR.any { name.contains(it, ignoreCase = true) } }
        if (outdoorWeather && habit != null) lines += context.getString(R.string.weather_alert_habit, habit)
        return lines.joinToString("\n")
    }

    // ── Settings and saved position ───────────────────────────────────────

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    private fun isEnabled(p: SharedPreferences) = p.getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) schedule(context) else cancel(context)
    }

    /**
     * Saved whenever Today fetches the day's weather in the foreground, so the
     * hourly check never needs background location (§16.3). ~1 km precision.
     */
    fun saveLocation(context: Context, lat: Double, lng: Double) {
        prefs(context).edit()
            .putFloat(KEY_LAT, TodayWeather.round2(lat).toFloat())
            .putFloat(KEY_LNG, TodayWeather.round2(lng).toFloat())
            .apply()
    }

    /** The last position saved above (~1 km), or null if Today never had one. Species ID uses it as "where you are" (§17.1). */
    fun savedLocation(context: Context): Pair<Double, Double>? {
        val p = prefs(context)
        if (!p.contains(KEY_LAT)) return null
        return p.getFloat(KEY_LAT, 0f).toDouble() to p.getFloat(KEY_LNG, 0f).toDouble()
    }

    /**
     * The alert to show under the header while alerts are on, it still applies
     * and the user has not closed it (§16.5), worded with today's [openHabits].
     */
    fun active(context: Context, openHabits: List<String>): String? {
        val p = prefs(context)
        if (!isEnabled(p) || System.currentTimeMillis() > p.getLong(KEY_ACTIVE_UNTIL, 0L)) return null
        val alerts = decode(p.getString(KEY_ACTIVE, null) ?: return null)
        if (alerts.isEmpty() || key(alerts) == p.getString(KEY_DISMISSED, null)) return null
        return text(context, alerts, openHabits)
    }

    /** Hides the current alert in the app; a different kind of alert shows again. */
    fun dismiss(context: Context) {
        val p = prefs(context)
        p.getString(KEY_ACTIVE, null)?.let { p.edit().putString(KEY_DISMISSED, key(decode(it))).apply() }
    }

    // ── Scheduling ────────────────────────────────────────────────────────

    private fun pendingIntent(context: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, REQUEST_CODE, Intent(context, Receiver::class.java), flags or PendingIntent.FLAG_IMMUTABLE)

    /** Hourly, inexact (batched by the OS). Idempotent; called on Today open, on enable and after boot/update. */
    fun schedule(context: Context) {
        if (!isEnabled(context)) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (pendingIntent(context, PendingIntent.FLAG_NO_CREATE) != null) return
        val pi = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_HOUR,
            pi,
        )
    }

    /** Re-register after the OS dropped every alarm (reboot, app update). */
    fun reschedule(context: Context) {
        pendingIntent(context, PendingIntent.FLAG_NO_CREATE)?.cancel()
        schedule(context)
    }

    private fun cancel(context: Context) {
        val pi = pendingIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(pi)
        pi.cancel()
    }

    // ── The hourly check ──────────────────────────────────────────────────

    suspend fun check(context: Context) {
        if (!isEnabled(context)) return
        val p = prefs(context)
        if (!p.contains(KEY_LAT)) return // Today has not fetched weather yet; nothing to go on
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        if (now.hour < DAY_START || now.hour >= DAY_END) return
        val json = WeatherManager.fetchAlertJson(p.getFloat(KEY_LAT, 0f).toDouble(), p.getFloat(KEY_LNG, 0f).toDouble()) ?: return
        // The forecast's hours are local to the saved place, which can differ from the phone's zone.
        val placeZone = ZoneOffset.ofTotalSeconds(json.optInt("utc_offset_seconds", zone.rules.getOffset(Instant.now()).totalSeconds))
        val placeNow = LocalDateTime.now(placeZone)
        val alerts = evaluate(json, placeNow)

        // In-app banner: whatever applies now, even if already notified.
        if (alerts.isEmpty()) {
            if (p.contains(KEY_ACTIVE)) p.edit().remove(KEY_ACTIVE).remove(KEY_ACTIVE_UNTIL).remove(KEY_DISMISSED).apply()
            return
        }
        val until = alerts.mapNotNull { it.at }.maxOrNull()?.plusHours(2) ?: placeNow.toLocalDate().plusDays(1).atStartOfDay()
        p.edit()
            .putString(KEY_ACTIVE, encode(alerts))
            .putLong(KEY_ACTIVE_UNTIL, until.atOffset(placeZone).toInstant().toEpochMilli())
            .apply()

        val today = now.toLocalDate()
        val nowMs = System.currentTimeMillis()
        val sent = SENT_KEYS.mapNotNull { k -> p.getString(k, null)?.let { k to it } }.toMap()
        val fresh = fresh(alerts, sent, nowMs, today)
        if (fresh.isEmpty()) return
        // The journal is only read when a notification is actually going out.
        val store = JournalStore.get(context)
        val open = CheckIns.open(store.readHabits(), store.todayTotals(), today).map { it.habit.name }
        if (!notify(context, text(context, fresh, open))) return
        val e = p.edit()
            .putString(KEY_COUNT_DAY, today.toString())
            .putString(KEY_COUNT, (sentToday(sent, today) + 1).toString())
        for (a in fresh) when {
            a.kind.daily -> e.putString("day:${a.kind}", today.toString())
            a.kind.precip -> e.putString("precipAt", nowMs.toString()).putString("precipSeverity", a.kind.severity.toString())
            else -> e.putString("at:${a.kind}", nowMs.toString())
        }
        e.apply()
    }

    /** False when notifications are off for the app or this channel. */
    private fun notify(context: Context, body: String): Boolean {
        NotificationKit.ensureChannel(context, CHANNEL_ID, R.string.weather_alert_channel, R.string.weather_alert_channel_description)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.weather_alert_title))
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(ScheduledNotificationReceiver.contentIntentFor(context, NOTIFICATION_ID))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        return NotificationKit.post(context, TAG, NOTIFICATION_ID, notification)
    }

    /** AlarmManager target for the hourly check. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = NotificationKit.runAsync(this, context, TAG) { check(it) }
    }
}
