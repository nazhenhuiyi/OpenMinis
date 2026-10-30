package com.openminis.app.ui.chat

/** One in-process check after an inserted user turn; never replays a tool itself. */
internal class InsertedTaskCheck {
    private var owed = false
    fun inserted(isScheduled: Boolean) { owed = !isScheduled }
    fun takeIfReady(empty: Boolean, cleanStop: Boolean, userWaiting: Boolean, turn: Int, cap: Int): String? {
        if (!owed || empty || !cleanStop || userWaiting || turn + 1 >= cap) return null
        owed = false
        return PROMPT
    }
    companion object {
        const val PROMPT = "<system-reminder>You answered a message inserted during ongoing work. Before ending, check the earlier task against the latest user instructions. If the user stopped, cancelled or replaced it, respect that and do not restart or re-delegate it. Otherwise, if requested work is still unfinished, continue only the missing work using the latest constraints. Check actual files first; do not repeat completed writes or other side effects. If it is complete, blocked, or still running in a child, say so accurately and stop. This is a one-time check, not permission to replay actions.</system-reminder>"
    }
}
