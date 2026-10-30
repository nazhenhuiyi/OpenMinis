package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.agent.jobs.HelperRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId

// [T-android-chat-calm] The everyday chat ("A + B"): a conversation that reads
// as a conversation. Background tasks are one quiet line under the reply that
// sent them, a capsule under the title counts what is still running or was
// interrupted, generated files are cards, and tool steps / timings / usage /
// the model name are gone.
//
// Nothing here removes the detailed renderer. Settings › Advanced › Developer
// mode brings back the chat exactly as it was, and every decision below is a
// pure function of that switch so the two styles cannot leak into each other.
// The composables live in ChatCalmUi.kt; this file is the testable half.

/** Settings › Advanced › Developer mode. Off by default. */
object ChatDeveloperMode {
    private const val PREFS = "chat_display_prefs"
    private const val KEY = "developer_mode"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    @Volatile private var loaded = false

    /** Reads the stored value once; later calls are free. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        _enabled.value = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
        loaded = true
    }

    fun set(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
        loaded = true
        _enabled.value = on
    }
}

/** How one tool_use block is drawn in the parent transcript. */
internal enum class ToolRowStyle {
    /** Developer mode: the existing agent card (HelperToolBlock). */
    HELPER_CARD,
    /** Developer mode: the existing tool capsule (ToolCallPill). */
    TOOL_PILL,
    /** Everyday: one quiet line for a background task. */
    TASK_LINE,
    /** Everyday: "正在搜索…" while an ordinary tool is still running. */
    ACTIVITY_LINE,
    /** Everyday: the stopped turn's Retry, kept as a quiet line. */
    RETRY_LINE,
    /** Everyday: a finished ordinary tool step, or a sub agent control call. */
    HIDDEN,
}

/** How an <agent_callback> message is drawn. */
internal enum class CallbackRowStyle {
    /** Developer mode: the existing callback card. */
    CARD,
    /**
     * Everyday: one quiet title line ("整理采购单 · 做完了") above the reply the
     * result started, so the reply says where it came from instead of reading
     * as the tail of whatever was said last.
     */
    RESULT_HEADER,
    HIDDEN,
}

/** What a finished (or progress) callback reports, in the user's terms. */
internal enum class CalmResultKind { DONE, FAILED, TIMEOUT, STOPPED, PROGRESS, ENDED }

internal fun calmResultKind(cb: com.openminis.app.agent.jobs.AgentCallback): CalmResultKind = when {
    cb.kind == com.openminis.app.agent.jobs.AgentCallback.Kind.PROGRESS -> CalmResultKind.PROGRESS
    cb.status == "done" || cb.status == "completed" -> CalmResultKind.DONE
    cb.status == "cancelled" -> CalmResultKind.STOPPED
    cb.status == "failed" || cb.status == "rejected" -> CalmResultKind.FAILED
    cb.status == "timeout" -> CalmResultKind.TIMEOUT
    else -> CalmResultKind.ENDED
}

internal object ChatPresentation {
    fun toolRow(
        isSubAgentTool: Boolean,
        isControlCall: Boolean,
        isRunning: Boolean,
        retryOffered: Boolean,
        developerMode: Boolean,
    ): ToolRowStyle = when {
        developerMode -> if (isSubAgentTool && !isControlCall) ToolRowStyle.HELPER_CARD else ToolRowStyle.TOOL_PILL
        // A control call (status / steer / cancel / resume) acts on a task that
        // already has its own line; showing it would be a second line for it.
        isSubAgentTool && isControlCall -> ToolRowStyle.HIDDEN
        isSubAgentTool -> ToolRowStyle.TASK_LINE
        isRunning -> ToolRowStyle.ACTIVITY_LINE
        retryOffered -> ToolRowStyle.RETRY_LINE
        else -> ToolRowStyle.HIDDEN
    }

    /**
     * The reply a result starts gets a title line saying which task it is
     * about. A stop the user asked for is the exception: the task's own line
     * already reads "已停止", so the callback (and the model's "好，停下了"
     * reply to it, see [calmVisibleRows]) adds nothing. A stop with no task
     * line to say so (a scheduled child, a truncated history) keeps the title.
     */
    fun callbackRow(kind: CalmResultKind, hasDelegateBlock: Boolean, developerMode: Boolean): CallbackRowStyle = when {
        developerMode -> CallbackRowStyle.CARD
        kind == CalmResultKind.STOPPED && hasDelegateBlock -> CallbackRowStyle.HIDDEN
        else -> CallbackRowStyle.RESULT_HEADER
    }

    fun showsFloatingToolBar(developerMode: Boolean, hasToolBlocks: Boolean): Boolean = developerMode && hasToolBlocks
    fun showsUsage(developerMode: Boolean): Boolean = developerMode
    fun showsModelSubtitle(developerMode: Boolean): Boolean = developerMode
    fun showsBackgroundCapsule(developerMode: Boolean, capsule: BackgroundCapsule): Boolean =
        !developerMode && capsule.visible
}

/** A background task's state in the user's terms. */
internal enum class CalmTaskState {
    STARTING, QUEUED, RUNNING, INTERRUPTED, NEVER_STARTED,
    COMPLETED, STOPPED, FAILED, TIMEOUT, NO_RESULT, ENDED;

    /** Something is in flight and can be stopped. */
    val isActive: Boolean get() = this == STARTING || this == QUEUED || this == RUNNING
    /** Needs the user: the work did not finish and will not resume by itself. */
    val needsUser: Boolean get() = this == INTERRUPTED || this == NEVER_STARTED
}

internal fun calmTaskState(info: HelperBlockInfo): CalmTaskState = when (val p = info.phase) {
    HelperPhase.Starting -> CalmTaskState.STARTING
    is HelperPhase.Queued -> CalmTaskState.QUEUED
    is HelperPhase.Running -> CalmTaskState.RUNNING
    HelperPhase.NeverStarted -> CalmTaskState.NEVER_STARTED
    is HelperPhase.Finished -> when (p.status) {
        "completed" -> CalmTaskState.COMPLETED
        "interrupted" -> CalmTaskState.INTERRUPTED
        "cancelled" -> CalmTaskState.STOPPED
        "failed", "rejected" -> CalmTaskState.FAILED
        "timeout" -> CalmTaskState.TIMEOUT
        HelperRunner.NO_DELIVERABLE -> CalmTaskState.NO_RESULT
        else -> CalmTaskState.ENDED
    }
}

/** One delegated task of this conversation, as the capsule and sheet see it. */
internal data class CalmTask(
    val block: AssistantBlock,
    val title: String,
    val state: CalmTaskState,
    val childSessionId: String?,
    /** When the delegation was made (or its message was saved), 0 when unknown. */
    val startedAtMs: Long,
)

internal data class BackgroundCapsule(val active: Int, val needsUser: Int) {
    val visible: Boolean get() = active > 0 || needsUser > 0
    /** Orange, "点这里继续": an interruption outranks work still going. */
    val urgent: Boolean get() = needsUser > 0
}

/**
 * An interruption only turns the capsule orange while it is recent. One left
 * from days ago would otherwise keep nagging forever; it still has its own
 * "中断了 · 继续" line in the conversation, and the sheet lists it whenever the
 * capsule is up for other work.
 */
internal const val CAPSULE_INTERRUPTION_WINDOW_MS = 24L * 60 * 60 * 1000

internal fun backgroundCapsule(tasks: List<CalmTask>, nowMs: Long): BackgroundCapsule =
    BackgroundCapsule(
        active = tasks.count { it.state.isActive },
        needsUser = tasks.count {
            it.state.needsUser && it.startedAtMs > 0 && nowMs - it.startedAtMs in 0..CAPSULE_INTERRUPTION_WINDOW_MS
        },
    )

/** Finished tasks started today, newest first — the sheet's "今天早些时候". */
internal fun earlierToday(
    tasks: List<CalmTask>,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<CalmTask> {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return tasks
        .filter { !it.state.isActive && !it.state.needsUser && it.startedAtMs > 0 }
        .filter { Instant.ofEpochMilli(it.startedAtMs).atZone(zone).toLocalDate() == today }
        .sortedByDescending { it.startedAtMs }
}

/**
 * Every real delegation in [messages] (control calls excluded), oldest first.
 * Pass the streaming-merged list so a task started in the live turn counts.
 */
internal fun collectCalmTasks(messages: List<ChatMessage>): List<CalmTask> {
    val out = ArrayList<CalmTask>()
    val seen = HashSet<String>()
    for (m in messages) {
        if (m.role != "assistant") continue
        for (b in m.toolBlocks) {
            if (b.kind != "tool_use" || !HelperRunner.isSubAgentToolName(b.toolName)) continue
            if (HelperRunner.classifyCall(b.toolArgs.ifEmpty { null }, b.content.ifEmpty { null }) !=
                HelperRunner.SubAgentCall.DELEGATE
            ) continue
            if (!seen.add(b.id)) continue
            val info = parseHelperBlock(b)
            out += CalmTask(
                block = b,
                title = info.title,
                state = calmTaskState(info),
                childSessionId = info.childSessionId,
                // A restored block has no start time; its message's own row
                // time is the closest record of when the task was sent.
                startedAtMs = b.startTimeMs.takeIf { it > 0 } ?: m.completedAt ?: 0L,
            )
        }
    }
    return out
}

/**
 * Message ids of queued user bubbles that start a run of queued bubbles. The
 * "等 Minis 说完再发" label is said once above the run, not on every bubble.
 */
internal fun queuedRunStarts(messages: List<ChatMessage>): Set<String> {
    val out = HashSet<String>()
    var prevQueued = false
    for (m in messages) {
        val queued = m.role == "user" && m.isQueued
        if (queued && !prevQueued) out += m.id
        prevQueued = queued
    }
    return out
}

/** A file the reply links to — shown as a card under the text. */
internal data class FileLink(val url: String, val label: String)

private val markdownLink = Regex("""(?<!!)\[([^\]\n]*)]\(\s*<?((?:minis|file)://[^)\s>]+|/var/minis/[^)\s>]+)>?\s*\)""")
private val bareMinisUrl = Regex("""(?<![(<\w])minis://[^\s)<>\]`"']+""")

/**
 * Local file links in a reply, in order, one per URL. Only candidates: the
 * caller still resolves each on disk and drops anything that is not an
 * existing file (deep-link actions, folders, missing files).
 */
internal fun extractFileLinks(markdown: String): List<FileLink> {
    if (!markdown.contains("minis://") && !markdown.contains("file://") && !markdown.contains("/var/minis/")) {
        return emptyList()
    }
    val found = LinkedHashMap<String, FileLink>()
    for (m in markdownLink.findAll(markdown)) {
        val url = m.groupValues[2]
        found.getOrPut(url) { FileLink(url, m.groupValues[1].trim().ifEmpty { fileNameOf(url) }) }
    }
    for (m in bareMinisUrl.findAll(markdown)) {
        val url = m.value.trimEnd('.', ',', ';', ':', '，', '。')
        found.getOrPut(url) { FileLink(url, fileNameOf(url)) }
    }
    return found.values.toList()
}

/**
 * True when a paragraph is nothing but file links (and list marks or
 * punctuation around them). The everyday chat then shows the cards alone
 * instead of the link text AND a card for the same file.
 */
internal fun isFileLinkOnly(markdown: String): Boolean {
    if (extractFileLinks(markdown).isEmpty()) return false
    val rest = bareMinisUrl.replace(markdownLink.replace(markdown, ""), "")
    return rest.all { it.isWhitespace() || it in "-*+•·,，、;；:：.。()（）《》<>" }
}

/** A reply's text with its file-link-only paragraphs taken out, for the everyday chat. */
internal data class CalmText(
    /** What the text renderer draws; blank when the text was only links. */
    val text: String,
    /** URLs whose link text was taken out; a card (or a plain label) stands in. */
    val removedLinkUrls: Set<String>,
)

/**
 * Paragraphs that are nothing but file links are dropped from the drawn text,
 * because the cards under it already show those files. Everything else is
 * left exactly as written, so a link inside a sentence stays a link.
 */
internal fun calmText(markdown: String): CalmText {
    if (extractFileLinks(markdown).isEmpty()) return CalmText(markdown, emptySet())
    val paragraphs = markdown.split(Regex("\n[ \t]*\n"))
    val (dropped, kept) = paragraphs.partition { isFileLinkOnly(it) }
    if (dropped.isEmpty()) return CalmText(markdown, emptySet())
    return CalmText(
        text = kept.joinToString("\n\n"),
        removedLinkUrls = dropped.flatMap { extractFileLinks(it) }.mapTo(LinkedHashSet()) { it.url },
    )
}

private fun fileNameOf(url: String): String {
    val last = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
    return runCatching { java.net.URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
}

private fun isDelegation(block: AssistantBlock): Boolean =
    HelperRunner.isSubAgentToolName(block.toolName) &&
        HelperRunner.classifyCall(block.toolArgs.ifEmpty { null }, block.content.ifEmpty { null }) ==
        HelperRunner.SubAgentCall.DELEGATE

/** The assistant message a row belongs to, without the dedupe suffix; null for non-assistant rows. */
internal fun assistantMessageIdOf(row: FlatChatItem): String? = when (row) {
    is FlatChatItem.AssistantHeader -> row.messageId
    is FlatChatItem.AssistantText -> row.messageId
    is FlatChatItem.AssistantMarkdownBlock -> row.messageId
    is FlatChatItem.AssistantThinking -> row.messageId
    is FlatChatItem.AssistantToolUse -> row.messageId
    is FlatChatItem.AssistantInfo -> row.messageId
    is FlatChatItem.AssistantTyping -> row.messageId
    is FlatChatItem.AssistantError -> row.messageId
    is FlatChatItem.AssistantUsage -> row.messageId
    is FlatChatItem.AssistantLegacyContent -> row.messageId
    else -> null
}?.substringBefore('#')

/** Rows that are the reply's words (or the wait for them), as opposed to its tasks and errors. */
private fun isReplyWords(row: FlatChatItem): Boolean =
    row is FlatChatItem.AssistantHeader || row is FlatChatItem.AssistantText ||
        row is FlatChatItem.AssistantMarkdownBlock || row is FlatChatItem.AssistantThinking ||
        row is FlatChatItem.AssistantTyping || row is FlatChatItem.AssistantLegacyContent ||
        row is FlatChatItem.AssistantUsage

private fun callbackStyle(row: FlatChatItem.AgentCallbackCard, delegateChildIds: Set<String>): CallbackRowStyle =
    ChatPresentation.callbackRow(
        kind = calmResultKind(row.callback),
        hasDelegateBlock = row.callback.childSessionId in delegateChildIds,
        developerMode = false,
    )

/**
 * Assistant messages that only acknowledge a stop the user asked for: every
 * callback that started them is a hidden stop. Their words are not drawn —
 * the task line already says "已停止" — but anything they DO (a new task line,
 * an error) still is.
 */
internal fun stopAcknowledgementIds(rows: List<FlatChatItem>, delegateChildIds: Set<String>): Set<String> {
    val out = HashSet<String>()
    val assigned = HashSet<String>()
    // The callbacks since the last thing the person said; an assistant
    // message takes the verdict of the run of callbacks right before it.
    var sawCallback = false
    var allHiddenStops = false
    var afterAssistant = true
    for (r in rows) {
        when (r) {
            is FlatChatItem.UserBubble, is FlatChatItem.ScheduledTaskCardItem -> {
                sawCallback = false; allHiddenStops = false; afterAssistant = false
            }
            is FlatChatItem.AgentCallbackCard -> {
                val hidden = callbackStyle(r, delegateChildIds) == CallbackRowStyle.HIDDEN
                if (afterAssistant || !sawCallback) {
                    // A new run of callbacks.
                    sawCallback = true; allHiddenStops = hidden
                } else {
                    allHiddenStops = allHiddenStops && hidden
                }
                afterAssistant = false
            }
            else -> {
                val id = assistantMessageIdOf(r) ?: continue
                afterAssistant = true
                if (assigned.add(id) && sawCallback && allHiddenStops) out += id
            }
        }
    }
    // A message that started a task is not "just an acknowledgement".
    val acting = rows.filterIsInstance<FlatChatItem.AssistantToolUse>()
        .filter { isDelegation(it.block) }
        .mapNotNullTo(HashSet()) { assistantMessageIdOf(it) }
    return out - acting
}

/**
 * The rows the everyday chat actually draws. Rows that would render nothing
 * are dropped rather than drawn at zero height, because the list spaces every
 * row: a turn of twenty tool steps would otherwise leave a visible gap. Run on
 * the flattened list so scroll targets index the rows that are on screen.
 */
internal fun calmVisibleRows(rows: List<FlatChatItem>): List<FlatChatItem> {
    val delegateChildIds = HashSet<String>()
    for (r in rows) {
        if (r is FlatChatItem.AssistantToolUse && isDelegation(r.block)) {
            HelperRunner.childSessionIdFrom(r.block.content)?.let { delegateChildIds += it }
        }
    }
    val stopAcks = stopAcknowledgementIds(rows, delegateChildIds)
    return rows.filter { r ->
        if (isReplyWords(r) && assistantMessageIdOf(r) in stopAcks) return@filter false
        when (r) {
            is FlatChatItem.AssistantUsage -> ChatPresentation.showsUsage(developerMode = false)
            is FlatChatItem.AssistantToolUse -> {
                val subAgent = HelperRunner.isSubAgentToolName(r.block.toolName)
                val running = r.block.toolStatus == ToolBlockStatus.RUNNING ||
                    r.block.toolStatus == ToolBlockStatus.PENDING ||
                    r.block.toolStatus == ToolBlockStatus.STREAMING
                ChatPresentation.toolRow(
                    isSubAgentTool = subAgent,
                    isControlCall = subAgent && !isDelegation(r.block),
                    isRunning = running,
                    // Kept while a retry MAY be offered; the renderer decides
                    // with the live streaming state and hides it otherwise.
                    retryOffered = r.isLastCancelled,
                    developerMode = false,
                ) != ToolRowStyle.HIDDEN
            }
            is FlatChatItem.AgentCallbackCard -> callbackStyle(r, delegateChildIds) != CallbackRowStyle.HIDDEN
            else -> true
        }
    }
}

/**
 * Where each task's result title line sits, by the task's child session: the
 * task line uses it to offer "看结果" when the result landed further down.
 * The latest result wins (a resumed task reports twice).
 */
internal fun resultHeaderKeysByChild(visibleRows: List<FlatChatItem>): Map<String, String> {
    val out = HashMap<String, String>()
    for (r in visibleRows) {
        if (r !is FlatChatItem.AgentCallbackCard) continue
        val child = r.callback.childSessionId ?: continue
        if (r.callback.kind != com.openminis.app.agent.jobs.AgentCallback.Kind.FINISHED) continue
        out[child] = r.key
    }
    return out
}

// ── Waiting for the first words ──────────────────────────────────────────

/** What the wait line says. Only elapsed time, never a made-up percentage. */
internal sealed class CalmWait {
    /** Just sent: no number yet, a count from zero reads as a stopwatch. */
    data object Starting : CalmWait()
    data class Thinking(val seconds: Long) : CalmWait()
    /** Long enough that saying so helps: real models often take 20 s+. */
    data class Slow(val seconds: Long) : CalmWait()
}

internal const val CALM_WAIT_COUNT_AFTER_MS = 3_000L
internal const val CALM_WAIT_LONG_AFTER_MS = 15_000L

internal fun calmWait(elapsedMs: Long): CalmWait = when {
    elapsedMs < CALM_WAIT_COUNT_AFTER_MS -> CalmWait.Starting
    elapsedMs < CALM_WAIT_LONG_AFTER_MS -> CalmWait.Thinking(elapsedMs / 1000)
    else -> CalmWait.Slow(elapsedMs / 1000)
}

/**
 * When the wait line for a reply first appeared, by message id. Kept outside
 * composition so scrolling the row away and back does not restart the count.
 */
internal object CalmWaitClock {
    private val started = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 64
        },
    )

    fun startOf(messageId: String, nowMs: Long): Long =
        started.getOrPut(messageId.substringBefore('#')) { nowMs }
}

// ── Long press on a reply ────────────────────────────────────────────────

/**
 * The user message to re-run for "重新回答" on [assistantMessageId], or null
 * when it is not offered: only the latest reply to something the person
 * typed. Re-running an older turn would cut away everything after it, and a
 * reply to a task result has no question of the user's to ask again.
 */
internal fun regenerateTargetFor(messages: List<ChatMessage>, assistantMessageId: String): String? {
    val id = assistantMessageId.substringBefore('#')
    val idx = messages.indexOfFirst { it.id == id }
    if (idx < 0 || messages[idx].role != "assistant") return null
    // Anything the user role carries after it (a message, a task result)
    // would be cut away by the re-run.
    if (messages.drop(idx + 1).any { it.role == "user" }) return null
    val trigger = messages.take(idx).lastOrNull { it.role == "user" } ?: return null
    return trigger.id.takeIf { trigger.rendersAsUserBubble() && !trigger.isQueued }
}

// ── Telling the user a task finished ─────────────────────────────────────

/**
 * Whether a finished background task deserves a notification. Only when the
 * person is not already looking at the conversation it reports into, and
 * never for a stop: they did that themselves, there is nothing to tell.
 */
internal fun shouldNotifyTaskResult(
    state: com.openminis.app.agent.jobs.AgentJobState,
    appForeground: Boolean,
    showingParentChat: Boolean,
): Boolean {
    if (state == com.openminis.app.agent.jobs.AgentJobState.CANCELLED) return false
    if (state == com.openminis.app.agent.jobs.AgentJobState.RUNNING ||
        state == com.openminis.app.agent.jobs.AgentJobState.PENDING
    ) return false
    return !(appForeground && showingParentChat)
}
