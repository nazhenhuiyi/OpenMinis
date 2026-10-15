package com.openminis.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The plumbing Today's own notifications share (weather alerts §16, the
 * evening check-in reminder §14.6): one channel each, a post that copes with
 * notifications being off, and a receiver body that runs off the main thread.
 */
internal object NotificationKit {
    fun ensureChannel(context: Context, id: String, nameRes: Int, descriptionRes: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(id) != null) return
        manager.createNotificationChannel(
            NotificationChannel(id, context.getString(nameRes), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(descriptionRes)
            },
        )
    }

    /** False when notifications are off for the app, or the permission is missing. */
    fun post(context: Context, tag: String, id: Int, notification: Notification): Boolean {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        return try {
            nm.notify(id, notification)
            true
        } catch (se: SecurityException) {
            AppLogger.info(tag, "notify denied (POST_NOTIFICATIONS not granted)")
            false
        }
    }

    /** A receiver's work on IO, bounded, with the broadcast kept alive until it ends. */
    fun runAsync(receiver: BroadcastReceiver, context: Context, tag: String, block: suspend (Context) -> Unit) {
        val pending = receiver.goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(20_000) { block(app) }
            } catch (t: Throwable) {
                AppLogger.warning(tag, "receiver failed: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
