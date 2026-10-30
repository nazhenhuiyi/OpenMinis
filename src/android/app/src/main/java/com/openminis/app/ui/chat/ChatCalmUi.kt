package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.ui.components.decorativePhase
import com.openminis.app.ui.components.decorativePingPong
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.sandbox.FileItem
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// [T-android-chat-calm] Everyday-chat composables. Logic in ChatCalmPresentation.kt.

/**
 * [T-android-chat-calm-polish] One set of measures for every everyday-chat
 * piece (task line, result title, file card, capsule, sheet, queued bubble),
 * so they read as one family. Spacing sits on a 4 dp grid; grey carries
 * hierarchy (primary → secondary → tertiary) and colour is kept for what can
 * be tapped or what reports a state (running violet, done green, needs-you
 * orange).
 */
internal object CalmSpec {
    val Space1: Dp = 4.dp
    val Space2: Dp = 8.dp
    val Space3: Dp = 12.dp
    val Space4: Dp = 16.dp

    /** Lines inside the conversation: task line, result title, activity, wait. */
    val LineSize: TextUnit = 14.sp
    val LineHeight: TextUnit = 20.sp
    /** Titles inside cards and the sheet. */
    val TitleSize: TextUnit = 15.sp
    val TitleHeight: TextUnit = 20.sp
    /** Result title tag and the sheet's hint: between a line and a caption. */
    val MetaSize: TextUnit = 13.sp
    /** Small print: capsule, sheet sub-lines, file details, queued label, buttons. */
    val CaptionSize: TextUnit = 12.sp
    val CaptionHeight: TextUnit = 16.sp

    /** A tappable line is at least this tall. */
    val LineMinHeight: Dp = 36.dp
    val GlyphBox: Dp = 16.dp
    val CardRadius: Dp = 12.dp
    val RowRadius: Dp = 8.dp
    val Hairline: Dp = 0.5.dp

    const val FadeMs = 180
}

/** Settings › Accessibility › Remove animations (animator scale 0). */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/** Read the developer-mode switch, loading it on first use. */
@Composable
internal fun rememberChatDeveloperMode(): Boolean {
    val context = LocalContext.current
    remember { ChatDeveloperMode.ensureLoaded(context) }
    val on by ChatDeveloperMode.enabled.collectAsState()
    return on
}

/** Interrupted / needs-the-user orange, readable on both themes. */
@Composable
internal fun calmAttention(): Color = if (ChatColors.isDark) Color(0xFFFFB347) else Color(0xFFB86200)

/**
 * Stop ONE background task — never the parent's reply. Through the registry
 * so the runner reads CANCELLED and a late result cannot revive it. Same path
 * as the developer-mode card's stop button.
 */
internal fun stopBackgroundTask(context: Context, block: AssistantBlock) {
    val childId = HelperRunner.childSessionIdFrom(block.content)
    val job = childId?.let { AgentJobRegistry.jobForSession(it) }
    if (job != null && job.isActive) {
        AgentJobRegistry.cancel(job.id, "user-stop-task")
    } else if (childId != null) runCatching {
        // [T-android-vm-store-child-tag-survives-evict] CHILD pool.
        HeadlessChatRunner.viewModelFor(context, childId, ChatViewModelStore.PoolKind.CHILD).cancelStream()
    }
}

/** The task's title; before the model has named it, the raw tool name would show — say "后台任务" instead. */
@Composable
private fun calmTaskTitle(task: CalmTask): String =
    task.title.takeUnless {
        it.isBlank() || it.replace('_', ' ').equals(task.block.toolName.replace('_', ' '), ignoreCase = true)
    }
        ?: stringResource(R.string.calm_task_untitled)

@Composable
private fun calmTaskLabel(task: CalmTask): String {
    val t = calmTaskTitle(task)
    return when (task.state) {
        CalmTaskState.STARTING -> stringResource(R.string.calm_task_starting, t)
        CalmTaskState.QUEUED -> stringResource(R.string.calm_task_queued, t)
        CalmTaskState.RUNNING -> stringResource(R.string.calm_task_running, t)
        CalmTaskState.INTERRUPTED -> stringResource(R.string.calm_task_interrupted, t)
        CalmTaskState.NEVER_STARTED -> stringResource(R.string.calm_task_never_started, t)
        CalmTaskState.COMPLETED -> stringResource(R.string.calm_task_done, t)
        CalmTaskState.STOPPED -> stringResource(R.string.calm_task_stopped, t)
        CalmTaskState.FAILED -> stringResource(R.string.calm_task_failed, t)
        CalmTaskState.TIMEOUT -> stringResource(R.string.calm_task_timeout, t)
        CalmTaskState.NO_RESULT -> stringResource(R.string.calm_task_no_result, t)
        CalmTaskState.ENDED -> stringResource(R.string.calm_task_ended, t)
    }
}

/** A soft dot that breathes while the task runs. Animated on the layer only; still when animations are off. */
@Composable
private fun BreathingDot(color: Color) {
    if (rememberReduceMotion()) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        return
    }
    val tick = rememberDecorativeTick()
    Box(
        Modifier
            .size(7.dp)
            .graphicsLayer {
                alpha = 0.35f + 0.65f * decorativePingPong(decorativePhase(tick.value, 1600))
            }
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun CalmTaskGlyph(state: CalmTaskState) {
    // A short cross-fade when the line changes state in place
    // (进行中 → 已完成 / 中断了), so the change is noticed but not staged.
    val reduce = rememberReduceMotion()
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            if (reduce) fadeIn(snap()) togetherWith fadeOut(snap())
            else fadeIn(tween(CalmSpec.FadeMs)) togetherWith fadeOut(tween(CalmSpec.FadeMs))
        },
        label = "calmTaskGlyph",
    ) { st ->
        Box(Modifier.size(CalmSpec.GlyphBox), contentAlignment = Alignment.Center) {
            when {
                st.isActive -> BreathingDot(helperAccent())
                st == CalmTaskState.COMPLETED -> Icon(
                    Icons.Default.Check, contentDescription = null,
                    tint = HelperStatusGreen, modifier = Modifier.size(14.dp),
                )
                st.needsUser -> Icon(
                    Icons.Default.Pause, contentDescription = null,
                    tint = calmAttention(), modifier = Modifier.size(14.dp),
                )
                else -> Box(Modifier.size(6.dp).clip(CircleShape).background(ChatColors.tertiaryText))
            }
        }
    }
}

/**
 * The line's action. "继续" / "开始" fill with the accent (the user is being
 * asked to act); "停止" is an outlined pill — clearly a button, but quieter,
 * since it is the less common choice; "看结果" is accent text.
 */
internal enum class CalmActionStyle { FILLED, OUTLINED, TEXT }

@Composable
internal fun CalmTaskAction(text: String, style: CalmActionStyle, enabled: Boolean = true, onClick: () -> Unit) {
    val accent = helperAccent()
    val shape = RoundedCornerShape(50)
    val color = when (style) {
        CalmActionStyle.OUTLINED -> ChatColors.secondaryText
        else -> accent
    }
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = CalmSpec.CaptionSize,
            lineHeight = CalmSpec.CaptionHeight,
            fontWeight = FontWeight.Medium,
            color = color.copy(alpha = if (enabled) 1f else 0.4f),
            maxLines = 1,
            modifier = Modifier
                .clip(shape)
                .then(
                    when (style) {
                        CalmActionStyle.FILLED -> Modifier.background(accent.copy(alpha = 0.12f))
                        CalmActionStyle.OUTLINED -> Modifier.border(1.dp, calmOutline(), shape)
                        CalmActionStyle.TEXT -> Modifier
                    },
                )
                .padding(horizontal = 10.dp, vertical = CalmSpec.Space1),
        )
    }
}

/** Outline for an outlined pill that reads on both themes: the separator grey, lightened. */
@Composable
internal fun calmOutline(): Color = ChatColors.separator.copy(alpha = if (ChatColors.isDark) 0.9f else 0.45f)

/**
 * The background task as one line under the reply that sent it. It changes
 * state in place ("进行中" → "已完成" / "中断了 · 继续"); tapping the line opens
 * the task's own record. When its result came back further down the
 * conversation, [onSeeResult] offers a jump there.
 */
@Composable
internal fun CalmTaskLine(
    task: CalmTask,
    onOpen: () -> Unit,
    onStop: (() -> Unit)?,
    onResume: (() -> Boolean)?,
    onStart: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onSeeResult: (() -> Unit)? = null,
) {
    var resuming by remember(task.block.id, task.state) { mutableStateOf(false) }
    val attention = task.state.needsUser
    val reduce = rememberReduceMotion()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CalmSpec.RowRadius))
            .clickable(onClickLabel = stringResource(R.string.calm_sheet_open_detail), onClick = onOpen)
            .heightIn(min = CalmSpec.LineMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CalmTaskGlyph(task.state)
        Spacer(Modifier.width(CalmSpec.Space2))
        AnimatedContent(
            targetState = calmTaskLabel(task),
            transitionSpec = {
                if (reduce) fadeIn(snap()) togetherWith fadeOut(snap())
                else fadeIn(tween(CalmSpec.FadeMs)) togetherWith fadeOut(tween(CalmSpec.FadeMs))
            },
            modifier = Modifier.weight(1f),
            label = "calmTaskLabel",
        ) { label ->
            Text(
                label,
                fontSize = CalmSpec.LineSize,
                lineHeight = CalmSpec.LineHeight,
                color = if (attention) calmAttention() else ChatColors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(CalmSpec.Space2))
        when {
            task.state == CalmTaskState.INTERRUPTED && onResume != null ->
                CalmTaskAction(stringResource(R.string.calm_task_continue), CalmActionStyle.FILLED, enabled = !resuming) {
                    resuming = onResume()
                }
            task.state == CalmTaskState.NEVER_STARTED && onStart != null ->
                CalmTaskAction(stringResource(R.string.calm_task_start), CalmActionStyle.FILLED) { onStart() }
            task.state.isActive && onStop != null ->
                CalmTaskAction(stringResource(R.string.calm_task_stop), CalmActionStyle.OUTLINED) { onStop() }
            onSeeResult != null ->
                CalmTaskAction(stringResource(R.string.calm_task_see_result), CalmActionStyle.TEXT) { onSeeResult() }
            else -> Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = ChatColors.tertiaryText, modifier = Modifier.size(CalmSpec.GlyphBox),
            )
        }
    }
}

@Composable
private fun calmResultLabel(title: String, kind: CalmResultKind): String = when (kind) {
    CalmResultKind.DONE -> stringResource(R.string.calm_result_done, title)
    CalmResultKind.FAILED -> stringResource(R.string.calm_result_failed, title)
    CalmResultKind.TIMEOUT -> stringResource(R.string.calm_result_timeout, title)
    CalmResultKind.STOPPED -> stringResource(R.string.calm_result_stopped, title)
    CalmResultKind.PROGRESS -> stringResource(R.string.calm_result_progress, title)
    CalmResultKind.ENDED -> stringResource(R.string.calm_result_ended, title)
}

/**
 * [T-android-chat-calm-polish] The title line a task's result arrives under:
 * "✓ 整理采购单 · 做完了 ›". A soft grey tag rather than a card, so it marks
 * where the reply came from without competing with it; tapping it opens the
 * task's record, like its task line does.
 */
@Composable
internal fun CalmResultHeader(
    title: String,
    kind: CalmResultKind,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = CalmSpec.Space3, bottom = CalmSpec.Space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(ChatColors.secondaryBg)
                .clickable(onClickLabel = stringResource(R.string.calm_sheet_open_detail), onClick = onOpen)
                .heightIn(min = 28.dp)
                .padding(start = CalmSpec.Space2, end = CalmSpec.Space1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(CalmSpec.GlyphBox), contentAlignment = Alignment.Center) {
                when (kind) {
                    CalmResultKind.DONE -> Icon(
                        Icons.Default.Check, contentDescription = null,
                        tint = HelperStatusGreen, modifier = Modifier.size(13.dp),
                    )
                    CalmResultKind.PROGRESS -> Box(Modifier.size(6.dp).clip(CircleShape).background(helperAccent()))
                    CalmResultKind.FAILED, CalmResultKind.TIMEOUT -> Icon(
                        Icons.Default.Pause, contentDescription = null,
                        tint = calmAttention(), modifier = Modifier.size(13.dp),
                    )
                    else -> Box(Modifier.size(6.dp).clip(CircleShape).background(ChatColors.tertiaryText))
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                calmResultLabel(title, kind),
                fontSize = CalmSpec.MetaSize,
                lineHeight = CalmSpec.CaptionHeight,
                fontWeight = FontWeight.Medium,
                color = ChatColors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = ChatColors.tertiaryText, modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * [T-android-chat-calm-polish] Waiting for the first words of a reply. Says
 * how long it has been — the one thing known for sure — and, past
 * [CALM_WAIT_LONG_AFTER_MS], that this one is slow. No invented stages.
 */
@Composable
internal fun CalmWaitLine(messageId: String, modifier: Modifier = Modifier) {
    val soul by com.openminis.app.agent.SoulStore.cachedMetadata.collectAsState()
    val name = soul.name.trim().ifEmpty { "Minis" }
    val started = remember(messageId) { CalmWaitClock.startOf(messageId, System.currentTimeMillis()) }
    var now by remember(messageId) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(messageId) {
        while (true) {
            kotlinx.coroutines.delay(1000L - (System.currentTimeMillis() - started) % 1000L)
            now = System.currentTimeMillis()
        }
    }
    val text = when (val w = calmWait(now - started)) {
        CalmWait.Starting -> stringResource(R.string.calm_wait_starting, name)
        is CalmWait.Thinking -> stringResource(R.string.calm_wait_thinking, name, w.seconds.toInt())
        is CalmWait.Slow -> stringResource(R.string.calm_wait_slow, name, w.seconds.toInt())
    }
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 28.dp).padding(top = CalmSpec.Space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(CalmSpec.GlyphBox), contentAlignment = Alignment.Center) { BreathingDot(helperAccent()) }
        Spacer(Modifier.width(CalmSpec.Space2))
        Text(text, fontSize = CalmSpec.LineSize, lineHeight = CalmSpec.LineHeight, color = ChatColors.tertiaryText)
    }
}

/** What a still-running ordinary tool says in the everyday chat. */
@Composable
internal fun CalmActivityLine(toolName: String, modifier: Modifier = Modifier) {
    val res = when (toolName) {
        "web_search" -> R.string.calm_activity_search
        "browser_use" -> R.string.calm_activity_browser
        "file_read", "read_image" -> R.string.calm_activity_read
        "file_write", "file_edit" -> R.string.calm_activity_write
        "shell_execute" -> R.string.calm_activity_shell
        "memory_write", "memory_get" -> R.string.calm_activity_memory
        else -> R.string.calm_activity_generic
    }
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(CalmSpec.GlyphBox), contentAlignment = Alignment.Center) { BreathingDot(ChatColors.tertiaryText) }
        Spacer(Modifier.width(CalmSpec.Space2))
        Text(stringResource(res), fontSize = CalmSpec.LineSize, lineHeight = CalmSpec.LineHeight, color = ChatColors.tertiaryText)
    }
}

/** The stopped turn's Retry, as a quiet line instead of a tool pill. */
@Composable
internal fun CalmRetryLine(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = CalmSpec.LineMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(CalmSpec.GlyphBox), contentAlignment = Alignment.Center) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(ChatColors.tertiaryText))
        }
        Spacer(Modifier.width(CalmSpec.Space2))
        Text(
            stringResource(R.string.calm_tool_stopped),
            fontSize = CalmSpec.LineSize, lineHeight = CalmSpec.LineHeight,
            color = ChatColors.secondaryText, modifier = Modifier.weight(1f),
        )
        CalmTaskAction(stringResource(R.string.calm_tool_retry), CalmActionStyle.OUTLINED, onClick = onRetry)
    }
}

/** "2 件事在后台进行 ›" under the title; orange when something was interrupted. */
@Composable
internal fun BackgroundCapsuleChip(capsule: BackgroundCapsule, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (capsule.urgent) calmAttention() else helperAccent()
    val text = if (capsule.urgent) {
        stringResource(R.string.calm_capsule_interrupted, capsule.needsUser)
    } else {
        stringResource(R.string.calm_capsule_running, capsule.active)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = if (ChatColors.isDark) 0.18f else 0.10f))
            .clickable(onClick = onClick)
            .heightIn(min = 24.dp)
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (capsule.urgent) {
            Icon(Icons.Default.Pause, contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
        } else {
            BreathingDot(color)
        }
        Text(
            text, fontSize = CalmSpec.CaptionSize, lineHeight = CalmSpec.CaptionHeight,
            fontWeight = FontWeight.Medium, color = color, maxLines = 1,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = color.copy(alpha = 0.7f), modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * The capsule's sheet: every task still going or interrupted, each with its own
 * stop / continue, then what finished earlier today. Tapping a row opens it.
 */
@Composable
internal fun BackgroundTasksSheet(
    tasks: List<CalmTask>,
    onDismiss: () -> Unit,
    onOpen: (CalmTask) -> Unit,
    onStop: (CalmTask) -> Unit,
    onResume: (CalmTask) -> Boolean,
    onStart: (CalmTask) -> Unit,
) {
    val active = tasks.filter { it.state.isActive }
    val needsUser = tasks.filter { it.state.needsUser }
    val earlier = remember(tasks) { earlierToday(tasks, System.currentTimeMillis()) }
    StandardChatSheet(title = stringResource(R.string.calm_sheet_title), onDismiss = onDismiss, heightFraction = 0.6f) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = CalmSpec.Space3),
        ) {
            Text(
                stringResource(R.string.calm_sheet_hint),
                fontSize = CalmSpec.MetaSize, lineHeight = 18.sp, color = ChatColors.tertiaryText,
            )
            if (active.isEmpty() && needsUser.isEmpty() && earlier.isEmpty()) {
                Text(
                    stringResource(R.string.calm_sheet_empty), fontSize = CalmSpec.LineSize, color = ChatColors.tertiaryText,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
            BackgroundTaskSection(stringResource(R.string.calm_sheet_interrupted), needsUser, onOpen, onStop, onResume, onStart)
            BackgroundTaskSection(stringResource(R.string.calm_sheet_running), active, onOpen, onStop, onResume, onStart)
            BackgroundTaskSection(stringResource(R.string.calm_sheet_earlier_today), earlier, onOpen, onStop, onResume, onStart)
        }
    }
}

@Composable
private fun BackgroundTaskSection(
    title: String,
    list: List<CalmTask>,
    onOpen: (CalmTask) -> Unit,
    onStop: (CalmTask) -> Unit,
    onResume: (CalmTask) -> Boolean,
    onStart: (CalmTask) -> Unit,
) {
    if (list.isEmpty()) return
    Text(
        title, fontSize = CalmSpec.CaptionSize, lineHeight = CalmSpec.CaptionHeight,
        fontWeight = FontWeight.Medium, color = ChatColors.tertiaryText,
        modifier = Modifier.padding(top = CalmSpec.Space4 + CalmSpec.Space1, bottom = CalmSpec.Space1),
    )
    list.forEachIndexed { i, task ->
        if (i > 0) HorizontalDivider(
            thickness = CalmSpec.Hairline, color = ChatColors.separator.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = CalmSpec.GlyphBox + CalmSpec.Space3),
        )
        BackgroundTaskSheetRow(
            task = task,
            onOpen = { onOpen(task) },
            onStop = { onStop(task) },
            onResume = { onResume(task) },
            onStart = { onStart(task) },
        )
    }
}

@Composable
private fun BackgroundTaskSheetRow(
    task: CalmTask,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onResume: () -> Boolean,
    onStart: () -> Unit,
) {
    var resuming by remember(task.block.id, task.state) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.calm_sheet_open_detail), onClick = onOpen)
            .heightIn(min = 56.dp)
            .padding(vertical = CalmSpec.Space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CalmTaskGlyph(task.state)
        Spacer(Modifier.width(CalmSpec.Space3))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val rowTitle = calmTaskTitle(task)
            Text(
                rowTitle, fontSize = CalmSpec.TitleSize, lineHeight = CalmSpec.TitleHeight, color = ChatColors.primaryText,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = calmTaskLabel(task).removePrefix(rowTitle).removePrefix(" · ")
            val context = LocalContext.current
            val time = if (!task.state.isActive && task.startedAtMs > 0) {
                // Time alone for today; an older interruption also says the day.
                val flags = if (android.text.format.DateUtils.isToday(task.startedAtMs)) {
                    android.text.format.DateUtils.FORMAT_SHOW_TIME
                } else {
                    android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or
                        android.text.format.DateUtils.FORMAT_ABBREV_MONTH
                }
                " · " + android.text.format.DateUtils.formatDateTime(context, task.startedAtMs, flags)
            } else ""
            Text(
                sub + time,
                fontSize = CalmSpec.CaptionSize, lineHeight = CalmSpec.CaptionHeight,
                color = if (task.state.needsUser) calmAttention() else ChatColors.tertiaryText,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(CalmSpec.Space2))
        when {
            task.state == CalmTaskState.INTERRUPTED ->
                CalmTaskAction(stringResource(R.string.calm_task_continue), CalmActionStyle.FILLED, enabled = !resuming) {
                    resuming = onResume()
                }
            task.state == CalmTaskState.NEVER_STARTED ->
                CalmTaskAction(stringResource(R.string.calm_task_start), CalmActionStyle.FILLED) { onStart() }
            task.state.isActive -> CalmTaskAction(stringResource(R.string.calm_task_stop), CalmActionStyle.OUTLINED) { onStop() }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = ChatColors.tertiaryText, modifier = Modifier.size(CalmSpec.GlyphBox),
        )
    }
}

/**
 * Files the reply links to, as cards under the text: tap opens the in-app
 * preview (the same path as tapping the link), the trailing button shares.
 * Only links that resolve to an existing file get a card.
 */
@Composable
internal fun CalmFileCards(
    markdown: String,
    sessionId: String?,
    onOpen: (String) -> Unit,
    /**
     * Links whose text was taken out of the drawn reply ([calmText]). One that
     * does not resolve to a file keeps a plain tappable label, so a missing
     * file still says so on tap instead of vanishing.
     */
    removedLinkUrls: Set<String> = emptySet(),
) {
    val links = remember(markdown) { extractFileLinks(markdown) }
    if (links.isEmpty()) return
    val context = LocalContext.current
    // null while resolving on disk.
    val resolved by produceState<List<Pair<FileLink, FileItem?>>?>(null, links, sessionId) {
        value = withContext(Dispatchers.IO) {
            links.map { link ->
                link to (ChatLinkResolver.resolve(link.url, sessionId, context) as? ChatLinkAction.SandboxFile)?.item
            }
        }
    }
    val rows = resolved ?: return
    val seen = HashSet<String>()
    val shown = rows.filter { (link, item) ->
        if (item == null) link.url in removedLinkUrls else seen.add(item.file.absolutePath)
    }
    if (shown.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(top = CalmSpec.Space2, bottom = CalmSpec.Space1),
        verticalArrangement = Arrangement.spacedBy(CalmSpec.Space2),
    ) {
        shown.forEach { (link, item) ->
            if (item != null) {
                CalmFileCard(item, onOpen = { onOpen(link.url) })
            } else {
                Text(
                    link.label,
                    fontSize = 16.sp,
                    color = ChatColors.link,
                    modifier = Modifier.clickable { onOpen(link.url) },
                )
            }
        }
    }
}

/** "MD · 1.2 KB": what kind of file and how big, instead of a generic hint. */
internal fun calmFileMeta(context: Context, item: FileItem): String {
    val ext = item.name.substringAfterLast('.', "").uppercase().takeIf { it.isNotEmpty() && it.length <= 5 }
    val size = android.text.format.Formatter.formatShortFileSize(context, item.size)
    return if (ext != null) "$ext · $size" else size
}

@Composable
private fun CalmFileCard(item: FileItem, onOpen: () -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(CalmSpec.CardRadius)
    // Light: a white card held by a faint outline. Dark: a lifted grey with
    // no outline (an outline on black reads as a box drawn around it).
    val bg = if (ChatColors.isDark) ChatColors.secondaryBg else ChatColors.background
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .then(if (ChatColors.isDark) Modifier else Modifier.border(1.dp, ChatColors.toolBorder, shape))
            .clickable(onClick = onOpen)
            .heightIn(min = 60.dp)
            .padding(start = CalmSpec.Space3, end = CalmSpec.Space1, top = CalmSpec.Space2, bottom = CalmSpec.Space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(CalmSpec.RowRadius))
                .background(ChatColors.link.copy(alpha = if (ChatColors.isDark) 0.18f else 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = ChatColors.link, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(CalmSpec.Space3))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.name, fontSize = CalmSpec.TitleSize, lineHeight = CalmSpec.TitleHeight,
                fontWeight = FontWeight.Medium, color = ChatColors.primaryText,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                remember(item.file.absolutePath, item.size) { calmFileMeta(context, item) },
                fontSize = CalmSpec.CaptionSize, lineHeight = CalmSpec.CaptionHeight, color = ChatColors.tertiaryText,
            )
        }
        IconButton(onClick = { com.openminis.app.ui.sandbox.shareFile(context, item) }) {
            Icon(
                Icons.Filled.IosShare,
                contentDescription = stringResource(R.string.calm_file_share),
                tint = ChatColors.tertiaryText,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}
