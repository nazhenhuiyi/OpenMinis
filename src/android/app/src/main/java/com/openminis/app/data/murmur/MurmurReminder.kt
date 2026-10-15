package com.openminis.app.data.murmur

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.openminis.app.R
import com.openminis.app.data.journal.JournalStore
import com.openminis.app.notification.NotificationKit
import com.openminis.app.offload.ScheduledNotificationReceiver

/**
 * [T-today-murmur] One-off reminders the user accepted under a murmur
 * (SPEC §19.3, "⏰ … 提醒我"). Each is a [MurmurSuggestion] in state
 * SCHEDULED; the alarm is keyed by the entry id. Inexact (a few minutes'
 * window), like the evening check-in reminder — no exact-alarm permission.
 * [rescheduleAll] runs when Today opens, so alarms dropped by a reboot come back.
 */
object MurmurReminder {
    private const val TAG = "MurmurReminder"
    private const val CHANNEL_ID = "minis_murmur_reminder"
    private const val EXTRA_ID = "id"
    private const val WINDOW_MS = 5 * 60 * 1000L
    /** Missed by more than this (phone off): dropped, not sent late. */
    private const val LATE_MS = 2 * 60 * 60 * 1000L

    private fun code(id: String) = 0x4D00_0000 or (id.hashCode() and 0x00FF_FFFF)

    private fun intent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context, code(id), Intent(context, Receiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(context: Context, id: String, atMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setWindow(AlarmManager.RTC_WAKEUP, atMs, WINDOW_MS, intent(context, id))
    }

    fun cancel(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(intent(context, id))
    }

    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        for ((id, s) in MurmurIndex.get(context).allSuggestions()) {
            if (s.state == MurmurSuggestion.SCHEDULED && s.atMs > now - LATE_MS) schedule(context, id, maxOf(s.atMs, now))
        }
    }

    private suspend fun fire(context: Context, id: String) {
        val s = MurmurIndex.get(context).allSuggestions()[id] ?: return
        if (s.state != MurmurSuggestion.SCHEDULED || System.currentTimeMillis() > s.atMs + LATE_MS) return
        // The murmur itself under the title: "止痛药还是牛……睡前得吃一颗", not a generic line.
        val body = JournalStore.get(context).find(id)?.second?.body?.replace(Regex("\\s+"), " ")?.take(120)
            ?: context.getString(R.string.murmur_reminder_from_today)
        NotificationKit.ensureChannel(context, CHANNEL_ID, R.string.murmur_reminder_channel, R.string.murmur_reminder_channel_description)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(s.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(ScheduledNotificationReceiver.contentIntentFor(context, code(id)))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        NotificationKit.post(context, TAG, code(id), n)
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = NotificationKit.runAsync(this, context, TAG) { app ->
            intent.getStringExtra(EXTRA_ID)?.let { fire(app, it) }
        }
    }
}
