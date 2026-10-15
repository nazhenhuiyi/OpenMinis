package com.openminis.app.notification

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.data.journal.CheckIns
import com.openminis.app.data.journal.HabitProgress
import com.openminis.app.data.journal.JournalStore
import com.openminis.app.offload.ScheduledNotificationReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [T-today-habit-reminder] One evening reminder for check-ins still open
 * today (docs/design/today/SPEC.md §14.6). At most one a day, and only when
 * something due today is unfinished; nothing is sent once all are done. The
 * text is a fixed template — no model, works offline.
 *
 * A one-shot inexact alarm at the chosen time; each run sets the next one.
 * While the reminder shows, it follows the journal ([observe]): items done
 * anywhere (Today, the agent, Obsidian, its own button) drop off it.
 */
object HabitReminder {
    private const val TAG = "HabitReminder"
    private const val PREFS = "habit_reminder"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MINUTE = "minuteOfDay"
    /** The day the reminder last ran its check, sent or not: one check a day. */
    private const val KEY_CHECKED_DAY = "checkedDay"
    /** When the pending alarm is set for, so a resume that would set the same one does nothing. */
    private const val KEY_NEXT_AT = "nextAt"
    /** The text last posted, or absent when the reminder is not showing (as far as we know). */
    private const val KEY_POSTED = "posted"
    const val DEFAULT_MINUTE = 20 * 60
    private const val CHANNEL_ID = "minis_habit_reminder"
    private const val NOTIFICATION_ID = 0x4AB1
    private const val REQUEST_ALARM = 0x4AB1
    private const val REQUEST_FILL = 0x4AB2
    /** The OS may hold an inexact alarm back by up to this much. */
    private const val WINDOW_MS = 10 * 60 * 1000L
    /** A reminder missed (phone off, alarm dropped) is still sent this long after its time, not later. */
    private const val LATE_MINUTES = 120L

    private val OPEN_PAGE = Uri.parse("minis://today/habits")
    private const val ACTION_FILL = "com.openminis.app.action.HABIT_REMINDER_FILL"
    private const val EXTRA_HABIT = "habit"

    // ── Rules (pure, unit-tested) ─────────────────────────────────────────

    /**
     * When the next check runs: today at [minute] if today's has not run yet
     * (or right away if that time passed less than [LATE_MINUTES] ago),
     * otherwise tomorrow at [minute].
     */
    fun nextRun(now: ZonedDateTime, minute: Int, checkedToday: Boolean): ZonedDateTime {
        val at = now.toLocalDate().atTime(LocalTime.of(minute / 60, minute % 60)).atZone(now.zone)
        return when {
            checkedToday -> at.plusDays(1)
            now.isBefore(at) -> at
            now.isBefore(at.plusMinutes(LATE_MINUTES)) -> now
            else -> at.plusDays(1)
        }
    }

    /** "俄罗斯转体 2/5 组 · 高抬腿 · 蛋白粉" */
    fun body(open: List<HabitProgress>): String =
        open.joinToString(" · ") { p -> listOfNotNull(p.habit.name, p.progressText?.takeIf { p.done > 0 }).joinToString(" ") }

    // ── Settings ──────────────────────────────────────────────────────────

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    private fun isEnabled(p: SharedPreferences) = p.getBoolean(KEY_ENABLED, true)

    fun minuteOfDay(context: Context): Int = prefs(context).getInt(KEY_MINUTE, DEFAULT_MINUTE).coerceIn(0, 24 * 60 - 1)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) schedule(context, force = true) else { cancelAlarm(context); clear(context) }
    }

    /** A time later than now today means "remind me today at that time", even if today's already ran. */
    fun setMinuteOfDay(context: Context, minute: Int) {
        val m = minute.coerceIn(0, 24 * 60 - 1)
        val e = prefs(context).edit().putInt(KEY_MINUTE, m)
        if (LocalTime.now().let { it.hour * 60 + it.minute } < m) e.remove(KEY_CHECKED_DAY)
        e.apply()
        schedule(context, force = true)
    }

    /**
     * False when the reminder could not be seen: notifications off for the app,
     * or this channel turned off in system settings. The page says so (§14.6).
     */
    fun canNotify(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val channel = ContextCompat.getSystemService(context, NotificationManager::class.java)?.getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** The system page that turns them back on: this channel's if only it is off, else the app's. */
    fun settingsIntent(context: Context): Intent {
        val appOff = !NotificationManagerCompat.from(context).areNotificationsEnabled()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appOff) {
            Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
        } else {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ── Scheduling ────────────────────────────────────────────────────────

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_ALARM, Intent(context, Receiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Set (or move) the one pending alarm. Called on Today open, after each run,
     * and with [force] on settings changes and after boot/update (when the OS has
     * dropped it). Without [force], an alarm already set for that time is left alone.
     */
    fun schedule(context: Context, force: Boolean = false) {
        val p = prefs(context)
        if (!isEnabled(p)) return
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val next = nextRun(now, minuteOfDay(context), p.getString(KEY_CHECKED_DAY, null) == now.toLocalDate().toString())
            .toInstant().toEpochMilli()
        if (!force && p.getLong(KEY_NEXT_AT, 0L) == next) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        // Inexact: no exact-alarm permission needed; a few minutes late is fine for this.
        am.setWindow(AlarmManager.RTC_WAKEUP, next, WINDOW_MS, alarmIntent(context))
        p.edit().putLong(KEY_NEXT_AT, next).apply()
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
        prefs(context).edit().remove(KEY_NEXT_AT).apply()
    }

    // ── The daily check ───────────────────────────────────────────────────

    suspend fun check(context: Context) {
        val p = prefs(context)
        if (!isEnabled(p)) return
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val today = now.toLocalDate()
        val minute = minuteOfDay(context)
        // Woken early (time moved later since) or already done today: nothing to do now.
        if (p.getString(KEY_CHECKED_DAY, null) == today.toString()) return
        if (now.hour * 60 + now.minute < minute) return
        p.edit().putString(KEY_CHECKED_DAY, today.toString()).apply()
        val open = readOpen(context, today)
        if (open.isNotEmpty()) notify(context, open, alert = true)
    }

    private suspend fun readOpen(context: Context, today: LocalDate): List<HabitProgress> {
        val store = JournalStore.get(context)
        return CheckIns.open(store.readHabits(), store.todayTotals(), today)
    }

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Started once with the app: every journal write (Today, the agent, the
     * notification's button, a file changed in Obsidian) updates a reminder
     * that is still showing — done items drop off, and it closes once nothing
     * is left. Costs nothing while no reminder is posted.
     */
    fun observe(context: Context) {
        val app = context.applicationContext
        watchScope.launch {
            JournalStore.get(app).changes.drop(1).collect { refresh(app) }
        }
    }

    private suspend fun refresh(context: Context) {
        val posted = prefs(context).getString(KEY_POSTED, null) ?: return
        // Swiped away or tapped (auto-cancel) since: forget it.
        if (!isShowing(context)) { prefs(context).edit().remove(KEY_POSTED).apply(); return }
        val open = readOpen(context, LocalDate.now())
        when {
            open.isEmpty() -> clear(context)
            body(open) != posted -> notify(context, open, alert = false)
        }
    }

    private fun isShowing(context: Context): Boolean {
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return false
        return runCatching { manager.activeNotifications.any { it.id == NOTIFICATION_ID } }.getOrDefault(false)
    }

    private fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        prefs(context).edit().remove(KEY_POSTED).apply()
    }

    private fun notify(context: Context, open: List<HabitProgress>, alert: Boolean) {
        NotificationKit.ensureChannel(context, CHANNEL_ID, R.string.habit_reminder_channel, R.string.habit_reminder_channel_description)
        val text = body(open)
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.resources.getQuantityString(R.plurals.habit_reminder_title, open.size, open.size))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(ScheduledNotificationReceiver.contentIntentFor(context, NOTIFICATION_ID, OPEN_PAGE))
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        // One item left: done straight from the notification.
        open.singleOrNull()?.habit?.id?.let { id ->
            val fill = Intent(context, Receiver::class.java).setAction(ACTION_FILL).putExtra(EXTRA_HABIT, id)
            val label = if (open.single().habit.target != null) R.string.today_habits_fill else R.string.today_done
            b.addAction(0, context.getString(label), PendingIntent.getBroadcast(context, REQUEST_FILL, fill, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (NotificationKit.post(context, TAG, NOTIFICATION_ID, b.build())) prefs(context).edit().putString(KEY_POSTED, text).apply()
    }

    /** "Fill up" on the notification. The journal write updates or closes the reminder ([observe]). */
    private suspend fun fill(context: Context, habitId: String) {
        val store = JournalStore.get(context)
        val habit = store.readHabits().firstOrNull { it.id == habitId && !it.archived } ?: return clear(context)
        store.completeCheckIn(habit)
    }

    /** AlarmManager target for the daily check, and the notification's "Fill up" button. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = NotificationKit.runAsync(this, context, TAG) { app ->
            if (intent.action == ACTION_FILL) {
                intent.getStringExtra(EXTRA_HABIT)?.let { fill(app, it) }
            } else {
                // This alarm has fired: always set the next one.
                try { check(app) } finally { schedule(app, force = true) }
            }
        }
    }
}
