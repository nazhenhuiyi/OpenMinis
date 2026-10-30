package com.openminis.app.ui.chat

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-chat-calm] The everyday chat ("A + B") versus developer mode.
 * Developer mode must render exactly what the chat rendered before; the
 * everyday chat swaps cards for quiet lines and adds the background capsule.
 */
class ChatCalmPresentationTest {

    private fun delegate(
        id: String,
        status: String,
        toolStatus: ToolBlockStatus,
        child: String? = "child-$id",
        title: String = "Task $id",
        startedAt: Long = 0L,
    ) = AssistantBlock(
        id = id,
        kind = "tool_use",
        content = buildString {
            append("""{"status":"$status"""")
            if (child != null) append(""","child_session_id":"$child"""")
            append("}")
        },
        toolName = "subagent_task",
        toolTitle = title,
        toolArgs = """{"task":"do $id","title":"$title"}""",
        toolStatus = toolStatus,
        startTimeMs = startedAt,
    )

    private fun control(id: String) = AssistantBlock(
        id = id, kind = "tool_use", content = """{"agents":[]}""",
        toolName = "subagent_task", toolTitle = "status", toolArgs = """{"action":"status"}""",
        toolStatus = ToolBlockStatus.SUCCESS,
    )

    private fun tool(id: String, status: ToolBlockStatus) = AssistantBlock(
        id = id, kind = "tool_use", content = "ok", toolName = "web_search", toolStatus = status,
    )

    private fun assistant(id: String, vararg blocks: AssistantBlock) =
        ChatMessage(id = id, role = "assistant", content = "", toolBlocks = blocks.toList())

    // ── Which renderer ───────────────────────────────────────────────────

    @Test
    fun `developer mode keeps the detailed card and pill for every tool row`() {
        for (running in listOf(true, false)) for (retry in listOf(true, false)) {
            assertEquals(ToolRowStyle.HELPER_CARD, ChatPresentation.toolRow(true, false, running, retry, developerMode = true))
            assertEquals(ToolRowStyle.TOOL_PILL, ChatPresentation.toolRow(true, true, running, retry, developerMode = true))
            assertEquals(ToolRowStyle.TOOL_PILL, ChatPresentation.toolRow(false, false, running, retry, developerMode = true))
        }
    }

    @Test
    fun `everyday chat shows tasks as lines and hides finished steps`() {
        assertEquals(ToolRowStyle.TASK_LINE, ChatPresentation.toolRow(true, false, true, false, developerMode = false))
        assertEquals(ToolRowStyle.TASK_LINE, ChatPresentation.toolRow(true, false, false, false, developerMode = false))
        assertEquals(ToolRowStyle.HIDDEN, ChatPresentation.toolRow(true, true, false, false, developerMode = false))
        assertEquals(ToolRowStyle.ACTIVITY_LINE, ChatPresentation.toolRow(false, false, true, false, developerMode = false))
        assertEquals(ToolRowStyle.RETRY_LINE, ChatPresentation.toolRow(false, false, false, true, developerMode = false))
        assertEquals(ToolRowStyle.HIDDEN, ChatPresentation.toolRow(false, false, false, false, developerMode = false))
    }

    @Test
    fun `chrome follows the switch`() {
        assertTrue(ChatPresentation.showsFloatingToolBar(developerMode = true, hasToolBlocks = true))
        assertFalse(ChatPresentation.showsFloatingToolBar(developerMode = false, hasToolBlocks = true))
        assertFalse(ChatPresentation.showsFloatingToolBar(developerMode = true, hasToolBlocks = false))
        assertTrue(ChatPresentation.showsUsage(true)); assertFalse(ChatPresentation.showsUsage(false))
        assertTrue(ChatPresentation.showsModelSubtitle(true)); assertFalse(ChatPresentation.showsModelSubtitle(false))
        val busy = BackgroundCapsule(active = 1, needsUser = 0)
        assertTrue(ChatPresentation.showsBackgroundCapsule(false, busy))
        assertFalse(ChatPresentation.showsBackgroundCapsule(true, busy))
        assertFalse(ChatPresentation.showsBackgroundCapsule(false, BackgroundCapsule(0, 0)))
    }

    @Test
    fun `callbacks are cards in developer mode and title lines in the everyday chat`() {
        for (k in CalmResultKind.values()) for (d in listOf(true, false)) {
            assertEquals(CallbackRowStyle.CARD, ChatPresentation.callbackRow(k, d, developerMode = true))
        }
        assertEquals(CallbackRowStyle.RESULT_HEADER, ChatPresentation.callbackRow(CalmResultKind.DONE, true, developerMode = false))
        assertEquals(CallbackRowStyle.RESULT_HEADER, ChatPresentation.callbackRow(CalmResultKind.FAILED, true, developerMode = false))
        assertEquals(CallbackRowStyle.RESULT_HEADER, ChatPresentation.callbackRow(CalmResultKind.PROGRESS, true, developerMode = false))
        // A stop the task line already reports says nothing more...
        assertEquals(CallbackRowStyle.HIDDEN, ChatPresentation.callbackRow(CalmResultKind.STOPPED, true, developerMode = false))
        // ...unless there is no task line to say it.
        assertEquals(CallbackRowStyle.RESULT_HEADER, ChatPresentation.callbackRow(CalmResultKind.STOPPED, false, developerMode = false))
    }

    @Test
    fun `callback status reads as a result kind`() {
        fun k(status: String, kind: com.openminis.app.agent.jobs.AgentCallback.Kind = com.openminis.app.agent.jobs.AgentCallback.Kind.FINISHED) =
            calmResultKind(callback("j", "c", status).copy(kind = kind))
        assertEquals(CalmResultKind.DONE, k("done"))
        assertEquals(CalmResultKind.STOPPED, k("cancelled"))
        assertEquals(CalmResultKind.FAILED, k("failed"))
        assertEquals(CalmResultKind.FAILED, k("rejected"))
        assertEquals(CalmResultKind.TIMEOUT, k("timeout"))
        assertEquals(CalmResultKind.ENDED, k("odd"))
        assertEquals(CalmResultKind.PROGRESS, k("running", com.openminis.app.agent.jobs.AgentCallback.Kind.PROGRESS))
    }

    private fun callback(job: String, child: String?, status: String, title: String = "整理采购单") =
        com.openminis.app.agent.jobs.AgentCallback(
            kind = com.openminis.app.agent.jobs.AgentCallback.Kind.FINISHED,
            jobId = job, childSessionId = child, title = title, status = status, body = "ok",
        )

    // ── Task states and the capsule ──────────────────────────────────────

    @Test
    fun `task states read in the user's terms`() {
        fun st(status: String, ts: ToolBlockStatus) = calmTaskState(parseHelperBlock(delegate("a", status, ts)))
        assertEquals(CalmTaskState.RUNNING, st("running", ToolBlockStatus.RUNNING))
        // A run the app lost mid-way is interrupted, never "running forever".
        assertEquals(CalmTaskState.INTERRUPTED, st("running", ToolBlockStatus.SUCCESS))
        assertEquals(CalmTaskState.COMPLETED, st("completed", ToolBlockStatus.SUCCESS))
        assertEquals(CalmTaskState.STOPPED, st("cancelled", ToolBlockStatus.CANCELLED))
        assertEquals(CalmTaskState.FAILED, st("failed", ToolBlockStatus.FAILED))
        assertEquals(CalmTaskState.TIMEOUT, st("timeout", ToolBlockStatus.TIMEOUT))
        assertEquals(CalmTaskState.ENDED, st("weird", ToolBlockStatus.SUCCESS))
        assertTrue(CalmTaskState.RUNNING.isActive && CalmTaskState.QUEUED.isActive && CalmTaskState.STARTING.isActive)
        assertFalse(CalmTaskState.STOPPED.isActive || CalmTaskState.STOPPED.needsUser)
        assertTrue(CalmTaskState.INTERRUPTED.needsUser && CalmTaskState.NEVER_STARTED.needsUser)
    }

    private val now = 1_800_000_000_000L
    private fun backgroundCapsule(tasks: List<CalmTask>) = backgroundCapsule(tasks, now)

    @Test
    fun `capsule counts running and interrupted tasks and hides when idle`() {
        val msgs = listOf(
            assistant("m1", delegate("a", "running", ToolBlockStatus.RUNNING), control("c1")),
            assistant("m2", delegate("b", "running", ToolBlockStatus.RUNNING), tool("t", ToolBlockStatus.SUCCESS)),
        )
        val tasks = collectCalmTasks(msgs)
        assertEquals(listOf("a", "b"), tasks.map { it.block.id })
        val two = backgroundCapsule(tasks)
        assertEquals(BackgroundCapsule(active = 2, needsUser = 0), two)
        assertTrue(two.visible); assertFalse(two.urgent)

        // One stopped by the user, the other still going: 1, not orange.
        val oneStopped = collectCalmTasks(listOf(
            assistant("m1", delegate("a", "cancelled", ToolBlockStatus.CANCELLED)),
            assistant("m2", delegate("b", "running", ToolBlockStatus.RUNNING)),
        ))
        assertEquals(BackgroundCapsule(1, 0), backgroundCapsule(oneStopped))

        // App killed mid-run: orange until the user continues it.
        val interrupted = backgroundCapsule(collectCalmTasks(listOf(
            assistant("m1", delegate("a", "running", ToolBlockStatus.SUCCESS, startedAt = now - 60_000)),
            assistant("m2", delegate("b", "completed", ToolBlockStatus.SUCCESS)),
        )))
        assertEquals(BackgroundCapsule(0, 1), interrupted)
        assertTrue(interrupted.visible && interrupted.urgent)

        // An interruption from days ago (or of unknown age) stays on its own
        // line and does not keep the capsule orange.
        val stale = backgroundCapsule(collectCalmTasks(listOf(
            assistant("m1", delegate("a", "running", ToolBlockStatus.SUCCESS, startedAt = now - 3L * 24 * 3600 * 1000)),
            assistant("m2", delegate("b", "running", ToolBlockStatus.SUCCESS, startedAt = 0L)),
        )))
        assertFalse(stale.visible)

        // After a restart the block has no start time; the saved message's
        // time stands in, so a fresh interruption is still orange.
        val restored = ChatMessage(
            id = "m1", role = "assistant", content = "", completedAt = now - 5 * 60_000,
            toolBlocks = listOf(delegate("a", "running", ToolBlockStatus.SUCCESS, startedAt = 0L)),
        )
        assertEquals(BackgroundCapsule(0, 1), backgroundCapsule(collectCalmTasks(listOf(restored))))

        // All done: no capsule.
        val idle = backgroundCapsule(collectCalmTasks(listOf(
            assistant("m1", delegate("a", "completed", ToolBlockStatus.SUCCESS)),
        )))
        assertFalse(idle.visible)
        assertFalse(backgroundCapsule(emptyList()).visible)
    }

    @Test
    fun `the same block across a merged list counts once`() {
        val b = delegate("a", "running", ToolBlockStatus.RUNNING)
        assertEquals(1, collectCalmTasks(listOf(assistant("m1", b), assistant("m1#2", b))).size)
    }

    @Test
    fun `earlier today lists finished tasks of today only, newest first`() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun at(h: Int, day: Int = 28) = LocalDateTime.of(2026, 10, day, h, 0).atZone(zone).toInstant().toEpochMilli()
        val now = at(15)
        val tasks = collectCalmTasks(listOf(assistant(
            "m",
            delegate("morning", "completed", ToolBlockStatus.SUCCESS, startedAt = at(9)),
            delegate("noon", "cancelled", ToolBlockStatus.CANCELLED, startedAt = at(12)),
            delegate("yesterday", "completed", ToolBlockStatus.SUCCESS, startedAt = at(20, day = 27)),
            delegate("live", "running", ToolBlockStatus.RUNNING, startedAt = at(14)),
            delegate("lost", "running", ToolBlockStatus.SUCCESS, startedAt = at(13)),
            delegate("undated", "completed", ToolBlockStatus.SUCCESS, startedAt = 0L),
        )))
        assertEquals(listOf("noon", "morning"), earlierToday(tasks, now, zone).map { it.block.id })
    }

    // ── Rows the everyday chat drops ─────────────────────────────────────

    @Test
    fun `everyday rows drop finished steps, usage and duplicate callbacks`() {
        val cb = com.openminis.app.agent.jobs.AgentCallback(
            kind = com.openminis.app.agent.jobs.AgentCallback.Kind.FINISHED,
            jobId = "j", childSessionId = "child-a", title = "Task a", status = "done", body = "ok",
        )
        val orphan = cb.copy(jobId = "j2", childSessionId = "child-zzz")
        val user = ChatMessage(id = "u", role = "user", content = "x")
        val rows = listOf(
            FlatChatItem.UserBubble(user),
            FlatChatItem.AssistantToolUse("m", delegate("a", "completed", ToolBlockStatus.SUCCESS), emptyList()),
            FlatChatItem.AssistantToolUse("m", control("c"), emptyList()),
            FlatChatItem.AssistantToolUse("m", tool("done", ToolBlockStatus.SUCCESS), emptyList()),
            FlatChatItem.AssistantToolUse("m", tool("live", ToolBlockStatus.RUNNING), emptyList()),
            FlatChatItem.AssistantToolUse("m", tool("stopped", ToolBlockStatus.CANCELLED), emptyList(), isLastCancelled = true),
            FlatChatItem.AssistantUsage("m", ChatTokenUsage(1, 1, 0, 0, 0), null),
            FlatChatItem.AgentCallbackCard(user.copy(id = "cb1"), cb),
            FlatChatItem.AgentCallbackCard(user.copy(id = "cb2"), orphan),
        )
        val kept = calmVisibleRows(rows).map { it.key }
        assertEquals(
            listOf("user:u", "tool:m:a", "tool:m:live", "tool:m:stopped", "callback:cb1", "callback:cb2"),
            kept,
        )
    }

    // ── How results come back ────────────────────────────────────────────

    private fun text(msg: String, t: String) = FlatChatItem.AssistantText(
        messageId = msg, block = AssistantBlock(id = "t-$msg", kind = "text", content = t), isStreaming = false, messageMarkdown = t,
    )

    /**
     * The conversation from the report: two tasks sent, a weather question
     * answered, then the stop of one task and the result of the other come
     * back. The result gets a title line; the stop and its "好，停下了" do not
     * show (the task line says 已停止); the weather answer is untouched.
     */
    private fun reportedConversation(): List<FlatChatItem> {
        val u = ChatMessage(id = "u1", role = "user", content = "两件事")
        val u2 = ChatMessage(id = "u2", role = "user", content = "下雨吗")
        val cbMsg = ChatMessage(id = "x", role = "user", content = "")
        return listOf(
            FlatChatItem.UserBubble(u),
            FlatChatItem.AssistantHeader("a1"),
            FlatChatItem.AssistantToolUse("a1", delegate("buy", "completed", ToolBlockStatus.SUCCESS), emptyList()),
            FlatChatItem.AssistantToolUse("a1", delegate("prep", "cancelled", ToolBlockStatus.CANCELLED), emptyList()),
            text("a1", "好，我分两件事在后台做。"),
            FlatChatItem.UserBubble(u2),
            FlatChatItem.AssistantHeader("a2"),
            text("a2", "周六多云转小雨。"),
            FlatChatItem.AgentCallbackCard(cbMsg.copy(id = "cbStop"), callback("j2", "child-prep", "cancelled", "写准备清单")),
            text("a3", "好，那件事已经停下了。"),
            FlatChatItem.AgentCallbackCard(cbMsg.copy(id = "cbDone"), callback("j1", "child-buy", "done")),
            text("a4", "采购单整理好了：一共 74.50 元。"),
        )
    }

    @Test
    fun `a result arrives under its own title line and a stop says nothing`() {
        val kept = calmVisibleRows(reportedConversation()).map { it.key }
        assertEquals(
            listOf(
                "user:u1", "header:a1", "tool:a1:buy", "tool:a1:prep", "text:a1:t-a1",
                "user:u2", "header:a2", "text:a2:t-a2",
                "callback:cbDone", "text:a4:t-a4",
            ),
            kept,
        )
        // Developer mode is the untouched list.
        assertEquals(12, reportedConversation().size)
    }

    @Test
    fun `stop acknowledgement covers only replies started by stops alone`() {
        val rows = reportedConversation()
        val delegates = setOf("child-buy", "child-prep")
        assertEquals(setOf("a3"), stopAcknowledgementIds(rows, delegates))

        // A stop and a result delivered back to back start one reply: it has
        // news, so it is drawn.
        val cbMsg = ChatMessage(id = "x", role = "user", content = "")
        val mixed = listOf(
            FlatChatItem.AgentCallbackCard(cbMsg.copy(id = "s"), callback("j2", "child-prep", "cancelled")),
            FlatChatItem.AgentCallbackCard(cbMsg.copy(id = "d"), callback("j1", "child-buy", "done")),
            text("r", "采购单好了，准备清单停了。"),
        )
        assertTrue(stopAcknowledgementIds(mixed, delegates).isEmpty())

        // A stop with no task line in view keeps its title and its reply.
        assertTrue(stopAcknowledgementIds(rows, setOf("child-buy")).isEmpty())

        // A reply that starts new work after a stop is not "just an ack".
        val acting = listOf(
            FlatChatItem.AgentCallbackCard(cbMsg.copy(id = "s"), callback("j2", "child-prep", "cancelled")),
            FlatChatItem.AssistantToolUse("r2", delegate("again", "running", ToolBlockStatus.RUNNING), emptyList()),
            text("r2", "我换个方式再试一次。"),
        )
        assertTrue(stopAcknowledgementIds(acting, delegates).isEmpty())

        // The person's own message always starts a fresh, normal reply.
        val after = rows + listOf(
            FlatChatItem.UserBubble(ChatMessage(id = "u3", role = "user", content = "谢谢")),
            text("a5", "不客气。"),
        )
        assertEquals(setOf("a3"), stopAcknowledgementIds(after, delegates))
    }

    @Test
    fun `while a stop acknowledgement streams its wait line is hidden too`() {
        val cbMsg = ChatMessage(id = "x", role = "user", content = "")
        val rows = listOf(
            FlatChatItem.UserBubble(ChatMessage(id = "u", role = "user", content = "两件事")),
            FlatChatItem.AssistantToolUse("a1", delegate("prep", "cancelled", ToolBlockStatus.CANCELLED), emptyList()),
            FlatChatItem.AgentCallbackCard(cbMsg.copy(id = "s"), callback("j2", "child-prep", "cancelled")),
            FlatChatItem.AssistantTyping("a3"),
        )
        assertEquals(listOf("user:u", "tool:a1:prep"), calmVisibleRows(rows).map { it.key })
    }

    @Test
    fun `task lines find their result title further down`() {
        val visible = calmVisibleRows(reportedConversation())
        assertEquals(mapOf("child-buy" to "callback:cbDone"), resultHeaderKeysByChild(visible))
        // The notification target works on either renderer's rows.
        assertEquals("callback:cbDone", resultRowKeyFor(reportedConversation(), "child-buy"))
        assertEquals("callback:cbStop", resultRowKeyFor(reportedConversation(), "child-prep"))
        assertEquals(null, resultRowKeyFor(reportedConversation(), "nobody"))
    }

    // ── Waiting, long press, notices ─────────────────────────────────────

    @Test
    fun `the wait line only ever reports elapsed time`() {
        assertEquals(CalmWait.Starting, calmWait(0))
        assertEquals(CalmWait.Starting, calmWait(2_999))
        assertEquals(CalmWait.Thinking(3), calmWait(3_000))
        assertEquals(CalmWait.Thinking(14), calmWait(14_999))
        assertEquals(CalmWait.Slow(15), calmWait(15_000))
        assertEquals(CalmWait.Slow(42), calmWait(42_500))
        // Scrolling away and back keeps the count.
        val t0 = CalmWaitClock.startOf("reply-1", 1_000)
        assertEquals(t0, CalmWaitClock.startOf("reply-1#2", 9_000))
    }

    @Test
    fun `answer again is offered only on the latest reply to a typed message`() {
        fun u(id: String, content: String = id) = ChatMessage(id = id, role = "user", content = content)
        val cb = callback("j", "c", "done").xml
        val msgs = listOf(u("q1"), assistant("r1"), u("q2"), assistant("r2"))
        assertEquals("q2", regenerateTargetFor(msgs, "r2"))
        assertEquals("q2", regenerateTargetFor(msgs, "r2#2"))
        assertEquals(null, regenerateTargetFor(msgs, "r1"))      // would cut q2 away
        assertEquals(null, regenerateTargetFor(msgs, "q2"))      // not a reply
        assertEquals(null, regenerateTargetFor(msgs, "missing"))
        // A reply to a task result has no question to ask again...
        assertEquals(null, regenerateTargetFor(msgs + listOf(u("cb", cb), assistant("r3")), "r3"))
        // ...and a result that arrived after a reply would be cut away.
        assertEquals(null, regenerateTargetFor(msgs + listOf(u("cb", cb)), "r2"))
    }

    @Test
    fun `a finished task is announced only when its chat is not on screen`() {
        assertTrue(shouldNotifyTaskResult(com.openminis.app.agent.jobs.AgentJobState.DONE, appForeground = false, showingParentChat = false))
        assertTrue(shouldNotifyTaskResult(com.openminis.app.agent.jobs.AgentJobState.DONE, appForeground = true, showingParentChat = false))
        assertTrue(shouldNotifyTaskResult(com.openminis.app.agent.jobs.AgentJobState.FAILED, appForeground = true, showingParentChat = false))
        assertTrue(shouldNotifyTaskResult(com.openminis.app.agent.jobs.AgentJobState.TIMEOUT, appForeground = false, showingParentChat = true))
        assertFalse(shouldNotifyTaskResult(com.openminis.app.agent.jobs.AgentJobState.DONE, appForeground = true, showingParentChat = true))
        // The person stopped it: nothing to tell.
        assertFalse(shouldNotifyTaskResult(com.openminis.app.agent.jobs.AgentJobState.CANCELLED, appForeground = false, showingParentChat = false))
        assertFalse(shouldNotifyTaskResult(com.openminis.app.agent.jobs.AgentJobState.RUNNING, appForeground = false, showingParentChat = false))
    }

    @Test
    fun `a chat under the Today overlay is not on screen`() {
        ChatScreenPresence.chatTab(true)
        ChatScreenPresence.resumed("main")
        assertTrue(ChatScreenPresence.isShowing("main"))
        ChatScreenPresence.chatTab(false)
        assertFalse(ChatScreenPresence.isShowing("main"))
        ChatScreenPresence.chatTab(true)
        assertTrue(ChatScreenPresence.isShowing("main"))
        ChatScreenPresence.paused("other")
        assertTrue(ChatScreenPresence.isShowing("main"))
        ChatScreenPresence.paused("main")
        assertFalse(ChatScreenPresence.isShowing("main"))
    }

    // ── Queued bubbles and file cards ────────────────────────────────────

    @Test
    fun `queued label is said once per run of queued bubbles`() {
        fun u(id: String, q: Boolean) = ChatMessage(id = id, role = "user", content = id, isQueued = q)
        val msgs = listOf(u("a", false), assistant("r"), u("q1", true), u("q2", true), assistant("r2"), u("q3", true))
        assertEquals(setOf("q1", "q3"), queuedRunStarts(msgs))
    }

    @Test
    fun `file links are found once each, images and web links ignored`() {
        val md = """
            采购单整理好了：[读书会采购单.md](minis://workspace/%E8%AF%BB%E4%B9%A6%E4%BC%9A%E9%87%87%E8%B4%AD%E5%8D%95.md)。
            清单在 minis://workspace/list.txt ，再看一次 [清单](minis://workspace/list.txt)。
            ![图](minis://attachments/a.png) 参考 [网页](https://example.com)
            绝对路径 [报告](/var/minis/workspace/report.html)
        """.trimIndent()
        val links = extractFileLinks(md)
        assertEquals(
            listOf(
                "minis://workspace/%E8%AF%BB%E4%B9%A6%E4%BC%9A%E9%87%87%E8%B4%AD%E5%8D%95.md",
                "minis://workspace/list.txt",
                "/var/minis/workspace/report.html",
            ),
            links.map { it.url },
        )
        assertEquals("读书会采购单.md", links[0].label)
        assertEquals("清单", links[1].label)
        assertTrue(extractFileLinks("没有链接的回复").isEmpty())
    }

    @Test
    fun `a paragraph of only file links is replaced by its cards`() {
        assertTrue(isFileLinkOnly("[读书会采购单.md](minis://workspace/a.md)"))
        assertTrue(isFileLinkOnly("- [采购单](minis://workspace/a.md)\n- [清单](minis://workspace/b.md)。"))
        assertTrue(isFileLinkOnly("minis://workspace/a.md"))
        assertFalse(isFileLinkOnly("采购单整理好了：[采购单](minis://workspace/a.md)"))
        assertFalse(isFileLinkOnly("[网页](https://example.com)"))
        assertFalse(isFileLinkOnly("没有链接"))
    }

    @Test
    fun `link-only paragraphs leave the drawn text, sentences keep their links`() {
        val md = "准备清单接着做完了。\n\n[清单.md](minis://workspace/b.md)\n\n好，那件事已经停下了。"
        val c = calmText(md)
        assertEquals("准备清单接着做完了。\n\n好，那件事已经停下了。", c.text)
        assertEquals(setOf("minis://workspace/b.md"), c.removedLinkUrls)

        val inline = "采购单在 [这里](minis://workspace/a.md)，看看。"
        assertEquals(CalmText(inline, emptySet()), calmText(inline))

        val only = calmText("[a.md](minis://workspace/a.md)")
        assertTrue(only.text.isBlank())
        assertEquals(setOf("minis://workspace/a.md"), only.removedLinkUrls)

        assertEquals(CalmText("普通回复", emptySet()), calmText("普通回复"))
    }

    // ── Wiring ───────────────────────────────────────────────────────────

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `settings has the developer mode switch, off by default`() {
        val settings = src("src/main/java/com/openminis/app/ui/settings/SettingsScreen.kt")
        assertTrue(settings.contains("R.string.settings_developer_mode"))
        assertTrue(settings.contains("ChatDeveloperMode.set(context, it)"))
        val pres = src("src/main/java/com/openminis/app/ui/chat/ChatCalmPresentation.kt")
        assertTrue(pres.contains(".getBoolean(KEY, false)"))
        assertTrue(pres.contains("MutableStateFlow(false)"))
    }

    @Test
    fun `the chat keeps the detailed renderer behind the switch`() {
        val cs = src("src/main/java/com/openminis/app/ui/chat/ChatScreen.kt")
        assertTrue(cs.contains("flatItems = if (developerMode) builtRows else calmVisibleRows(builtRows)"))
        assertTrue(cs.contains("ToolRowStyle.HELPER_CARD, ToolRowStyle.TOOL_PILL ->"))
        assertTrue(cs.contains("ChatPresentation.showsFloatingToolBar(developerMode"))
        // Stopping a task goes through the registry, not the parent's stream.
        val ui = src("src/main/java/com/openminis/app/ui/chat/ChatCalmUi.kt")
        assertTrue(ui.contains("AgentJobRegistry.cancel(job.id, \"user-stop-task\")"))
        assertFalse(ui.contains("cancelStream(stopBackgroundTasks"))
    }

    @Test
    fun `a task notice opens the chat at the result and silences the generic one`() {
        assertEquals("focusTask", com.openminis.app.notification.TaskResultNotifier.FOCUS_TASK_PARAM)
        val deeplink = src("src/main/java/com/openminis/app/deeplink/DeepLinkHandler.kt")
        assertTrue(deeplink.contains("getQueryParameter(\"focusTask\")"))
        assertTrue(src("src/main/java/com/openminis/app/MainActivity.kt").contains("ChatResultFocus.request(action.sessionId, it)"))
        assertTrue(src("src/main/java/com/openminis/app/notification/BackgroundTaskNotifier.kt").contains("TaskResultNotifier.spokeRecentlyFor(sessionId)"))
        assertFalse(com.openminis.app.notification.TaskResultNotifier.spokeRecentlyFor("never-notified"))
        // Started from the app, gated by the same switch and the permission.
        assertTrue(src("src/main/java/com/openminis/app/MinisApp.kt").contains("TaskResultNotifier("))
        val n = src("src/main/java/com/openminis/app/notification/TaskResultNotifier.kt")
        assertTrue(n.contains("taskNotificationsEnabled.value"))
        assertTrue(n.contains("areNotificationsEnabled()"))
    }
}
