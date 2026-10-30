package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-android-chat-calm-polish] Which conversation is on screen right now, so a
 * finished background task is announced only when the person is NOT already
 * looking at the chat it reports into.
 *
 * ChatScreen says when it is resumed and when it pauses. That alone is not
 * "on screen": Today and Explore are drawn as an overlay above the chat (see
 * MainTabs), so a chat can stay resumed underneath them — [isShowing] also
 * requires the Chat tab.
 */
object ChatScreenPresence {
    private val _resumed = MutableStateFlow<String?>(null)

    /** The resumed chat's session id while the Chat tab shows it, else null. */
    val showing: StateFlow<String?> get() = _showing.asStateFlow()
    private val _showing = MutableStateFlow<String?>(null)

    @Volatile private var chatTabSelected = true

    fun resumed(sessionId: String) {
        _resumed.value = sessionId
        publish()
    }

    fun paused(sessionId: String) {
        if (_resumed.value == sessionId) _resumed.value = null
        publish()
    }

    fun chatTab(selected: Boolean) {
        chatTabSelected = selected
        publish()
    }

    fun isShowing(sessionId: String): Boolean = _showing.value == sessionId

    private fun publish() {
        _showing.value = _resumed.value.takeIf { chatTabSelected }
    }
}

/**
 * [T-android-chat-calm-polish] "Open this conversation at that task's result" —
 * left by a task-done notification's deep link, taken by the ChatScreen of
 * [sessionId] once the result's row is in its list.
 */
object ChatResultFocus {
    data class Request(val sessionId: String, val childSessionId: String, val atMs: Long = System.currentTimeMillis())

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    fun request(sessionId: String, childSessionId: String) {
        _pending.value = Request(sessionId, childSessionId)
    }

    fun consume(r: Request) {
        _pending.compareAndSet(r, null)
    }
}

/**
 * The row a task's result is shown on, in either renderer: the last finished
 * callback from [childSessionId]. Null until it has been flattened.
 */
internal fun resultRowKeyFor(rows: List<FlatChatItem>, childSessionId: String): String? =
    rows.lastOrNull {
        it is FlatChatItem.AgentCallbackCard &&
            it.callback.childSessionId == childSessionId &&
            it.callback.kind == com.openminis.app.agent.jobs.AgentCallback.Kind.FINISHED
    }?.key

/** Ask for the notification permission at most once, the first time a task goes to the background. */
internal object ChatTaskNoticeAsk {
    private const val PREFS = "chat_display_prefs"
    private const val KEY = "asked_task_notice_permission"

    fun shouldAsk(context: android.content.Context): Boolean =
        !context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun markAsked(context: android.content.Context) {
        context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, true).apply()
    }
}
