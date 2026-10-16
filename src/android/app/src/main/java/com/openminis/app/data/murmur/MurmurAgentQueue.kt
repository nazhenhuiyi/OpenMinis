package com.openminis.app.data.murmur

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * [T-today-murmur-agent] Smart suggestions' background work (SPEC §19.3), owned
 * by the app rather than a screen: a chat Minis is preparing is not cut off
 * (and thrown away) when Today's view model goes, the way a scheduled task is
 * not. Today hands over the murmurs that passed the sift and observes.
 *
 * One queue, one at a time: preparing murmurs and clearing out chats never
 * opened go through the same line, so they cannot step on each other.
 */
object MurmurAgentQueue {
    private const val TAG = "MurmurAgentQueue"

    data class Item(val id: String, val body: String, val atMs: Long)

    private sealed interface Task {
        data class Prepare(val item: Item) : Task
        data class Clear(val all: Boolean) : Task
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tasks = Channel<Task>(Channel.UNLIMITED)
    @Volatile private var started = false

    /** id + text already handed over this run: each version of a murmur goes to Minis once. */
    private val queued = HashSet<String>()

    private val _working = MutableStateFlow<String?>(null)
    /** The murmur Minis is looking at right now: "Minis 正在看这条…" under it. */
    val working: StateFlow<String?> = _working.asStateFlow()

    private val _changes = MutableStateFlow(0)
    /** Goes up whenever a suggestion was stored or a chat cleared: Today shows them again. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val _fresh = MutableStateFlow<Set<String>>(emptySet())
    /** Murmurs whose suggestion was made in this run and not yet seen: their card appears with a fade, once. */
    val fresh: StateFlow<Set<String>> = _fresh.asStateFlow()

    /** The card for [id] has appeared: from now on it simply shows. */
    fun seen(id: String) = _fresh.update { it - id }

    private var clearedOn: LocalDate? = null
    private var clearedAll = false

    /** Hand murmurs over (newest first); ones already handed over this run are skipped. */
    @Synchronized
    fun enqueue(context: Context, items: List<Item>) {
        clearedAll = false
        val app = context.applicationContext
        val today = LocalDate.now()
        if (clearedOn != today) { clearedOn = today; send(app, Task.Clear(all = false)) }
        for (it in items) if (queued.add(it.id + MurmurIndex.hash(it.body))) send(app, Task.Prepare(it))
    }

    /** 智能建议 is off: every chat never opened goes, once. */
    @Synchronized
    fun clearAll(context: Context) {
        if (clearedAll) return
        clearedAll = true
        send(context.applicationContext, Task.Clear(all = true))
    }

    private fun send(app: Context, task: Task) {
        tasks.trySend(task)
        if (!started) {
            started = true
            scope.launch { for (t in tasks) runCatching { run(app, t) }.onFailure { AppLogger.warning(TAG, "task failed: ${it.message}") } }
        }
    }

    private suspend fun run(app: Context, task: Task) {
        val index = MurmurIndex.get(app)
        when (task) {
            is Task.Prepare -> {
                val (id, body, atMs) = task.item
                // Redone, or the murmur was edited since: the chat from before goes first.
                // A chat the user opened is theirs now and stays.
                index.rawSuggestion(id)?.takeIf { it.sessionId.isNotBlank() && it.state != MurmurSuggestion.OPENED }?.let { old ->
                    runCatching { MurmurAgent.discard(app, old.sessionId) }
                    withContext(Dispatchers.IO) { index.putSuggestion(id, old.copy(sessionId = "")) }
                }
                _working.value = id
                val s = try {
                    MurmurAgent.prepare(app, body, atMs, MurmurIndex.hash(body))
                } finally {
                    _working.value = null
                }
                if (s != null) {
                    withContext(Dispatchers.IO) { index.putSuggestion(id, s) }
                    if (s.state == MurmurSuggestion.OFFER) _fresh.update { it + id }
                }
                _changes.update { it + 1 }
            }
            is Task.Clear -> {
                // Chats never opened that the 智能建议 page no longer lists (turned down, or
                // older than [MurmurAgent.KEEP_DAYS]) — or, [all], every one.
                val cleared = HashMap<String, MurmurSuggestion>()
                for ((id, s) in index.allSuggestions()) {
                    if (s.sessionId.isBlank() || s.state == MurmurSuggestion.OPENED) continue
                    if (!task.all && s.isLive && MurmurAgent.isRecent(s)) continue
                    runCatching { MurmurAgent.discard(app, s.sessionId) }
                    cleared[id] = s.copy(sessionId = "")
                }
                if (cleared.isNotEmpty()) {
                    withContext(Dispatchers.IO) { index.putSuggestions(cleared) }
                    _changes.update { it + 1 }
                }
            }
        }
    }
}
