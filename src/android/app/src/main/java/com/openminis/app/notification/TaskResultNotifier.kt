package com.openminis.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.agent.jobs.AgentJobThen
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatScreenPresence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-android-chat-calm-polish] "整理采购单 · 做完了" when a background task the
 * conversation delegated finishes while the person is not looking at that
 * conversation — the app is in the background, or open on another tab or
 * screen. Tapping it opens the conversation at the task's result.
 *
 * Why a second notifier beside [BackgroundTaskNotifier]: that one reports a
 * SESSION's turn ending, and only while the app is in the background, with a
 * generic "task completed". What the person is waiting for here is one named
 * task, and they may well be inside the app on Today when it lands. When this
 * one has spoken for a conversation, the generic one stays quiet for it (see
 * [recentlyNotified]) so a finished task is announced once, not twice.
 *
 * Rules: never for a stop (the person did that), never while that chat is on
 * screen, never for hidden sessions, only under the Task Notifications switch
 * and the system notification permission.
 */
class TaskResultNotifier(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val backgroundSettings: BackgroundSettingsRepository,
    private val isAppForeground: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        ensureChannel()
        scope.launch {
            AgentJobRegistry.completions.collect { c ->
                runCatching { onCompletion(c.jobId, c.state) }
                    .onFailure { AppLogger.warning(TAG, "task notice failed: ${it.message}") }
            }
        }
        // Looking at the conversation is reading the result: clear its notices.
        scope.launch {
            ChatScreenPresence.showing.collect { sid -> if (sid != null) clearFor(sid) }
        }
    }

    private suspend fun onCompletion(jobId: String, state: AgentJobState) {
        val job = AgentJobRegistry.job(jobId) ?: return
        if (job.then !is AgentJobThen.FollowUpParent) return
        val parent = job.target.parentSessionIdOrNull ?: return
        if (AgentJobRegistry.isDelegationMuted(parent)) return
        val show = com.openminis.app.ui.chat.shouldNotifyTaskResult(
            state = state,
            appForeground = isAppForeground(),
            showingParentChat = ChatScreenPresence.isShowing(parent),
        )
        if (!show) return
        if (!backgroundSettings.taskNotificationsEnabled.value) return
        val session = chatRepository.getSession(parent) ?: return
        if (session.isHiddenFromLists) return
        val title = context.getString(
            if (state == AgentJobState.DONE) R.string.calm_notif_done_title else R.string.calm_notif_failed_title,
            job.title.ifBlank { context.getString(R.string.helper_label) },
        )
        post(parent, job.runSessionId, jobId, title)
    }

    private fun post(parentSessionId: String, childSessionId: String?, jobId: String, title: String) {
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            AppLogger.info(TAG, "task notice skipped: notifications not allowed")
            return
        }
        val uri = Uri.parse("minis://session/$parentSessionId").buildUpon()
            .apply { if (childSessionId != null) appendQueryParameter(FOCUS_TASK_PARAM, childSessionId) }
            .build()
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val id = noticeId(jobId)
        val pending = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.calm_notif_body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .build()
        try {
            nm.notify(id, n)
            recentlyNotified[parentSessionId] = System.currentTimeMillis()
            posted.getOrPut(parentSessionId) { ConcurrentHashMap.newKeySet() }.add(id)
            AppLogger.info(TAG, "task notice posted job=${jobId.take(8)} parent=${parentSessionId.take(8)}")
        } catch (se: SecurityException) {
            AppLogger.info(TAG, "task notice denied (POST_NOTIFICATIONS not granted)")
        }
    }

    private fun clearFor(sessionId: String) {
        val ids = posted.remove(sessionId) ?: return
        val nm = NotificationManagerCompat.from(context)
        ids.forEach { runCatching { nm.cancel(it) } }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // High importance so it can show as a banner while the app is open
        // on another tab; no sound and one short buzz, so it stays light.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.calm_notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.calm_notif_channel_description)
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 40L)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "TaskResultNotifier"
        const val CHANNEL_ID = "minis_task_result"
        /** `minis://session/<id>?focusTask=<child session>` — open at that task's result. */
        const val FOCUS_TASK_PARAM = "focusTask"

        private val recentlyNotified = ConcurrentHashMap<String, Long>()
        private val posted = ConcurrentHashMap<String, MutableSet<Int>>()

        /** How long the generic "turn finished" notice stays quiet after a task notice. */
        const val QUIET_AFTER_TASK_NOTICE_MS = 3 * 60_000L

        /** True when a task notice already told the person about [sessionId] just now. */
        fun spokeRecentlyFor(sessionId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
            val at = recentlyNotified[sessionId] ?: return false
            return nowMs - at in 0..QUIET_AFTER_TASK_NOTICE_MS
        }

        internal fun noticeId(jobId: String): Int = ("task-result:$jobId").hashCode()
    }
}
