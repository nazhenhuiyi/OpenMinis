package com.openminis.app.ui.today

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.notification.HabitReminder
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.journal.Habit
import com.openminis.app.data.journal.HabitProgress
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * [T-today-habits] Today's check-in progress in the top bar
 * (docs/design/today/SPEC.md §14.3): a small ring and "2/3". Tapping it opens
 * [HabitsPage]. Takes no room from the timeline.
 *
 * Empty [habits] = the user has no check-ins yet: a faint "打卡" ghost that
 * leads to the same page, so the feature can be found without the ⋯ menu.
 */
@Composable
fun HabitsChip(habits: List<HabitProgress>, onClick: () -> Unit) {
    val done = habits.count { it.complete }
    val ghost = habits.isEmpty()
    val all = !ghost && done == habits.size
    val label = if (ghost) stringResource(R.string.today_habits_ghost) else stringResource(R.string.today_habits_progress, done, habits.size)
    val description = if (ghost) stringResource(R.string.today_habits_add) else stringResource(R.string.today_habits_a11y, done, habits.size)
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 10.dp).testTag("today_habits_chip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProgressRing(
            fraction = if (ghost) 0f else done.toFloat() / habits.size,
            complete = all,
            size = 18.dp,
            stroke = 2.2.dp,
            checkSize = 12.dp,
            dimmed = ghost,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            fontSize = 13.sp,
            color = when {
                all -> MaterialTheme.colorScheme.primary
                ghost -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * A day's check-ins as one block at the top of the day (§14.3): a quiet
 * "打卡 2/3" caption over one line per habit. Everything listed is counted,
 * so the caption always matches what you see. Tap and long-press both open
 * [HabitsPage] with that item's adjust panel open — in the timeline nothing
 * is recorded by a single touch; the page is the quick way.
 */
@Composable
fun CheckInBlock(items: List<HabitProgress>, onAdjust: (String) -> Unit) {
    val done = items.count { it.complete }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("today_checkins")) {
        Text(
            stringResource(R.string.today_habits_summary) + "  ·  " + stringResource(R.string.today_habits_progress, done, items.size),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(bottom = 2.dp),
        )
        for (p in items) CheckInRow(p, large = false, onStep = null, onAdjust = onAdjust)
    }
}

/**
 * One habit: ring, name, "30/50 个". Editable rows: with [onComplete], tap
 * completes an open habit; otherwise (or on a done one) tap adjusts, as does
 * long-press — a stray tap never undoes anything silently. Read-only rows
 * (past days) ignore touches.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CheckInRow(p: HabitProgress, large: Boolean, onStep: ((habitId: String, by: Int) -> Unit)?, onAdjust: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val id = p.habit.id
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                enabled = p.editable && id != null,
                // Page: each tap records one set, sized to the goal (§14.3); a done item opens adjust.
                onClick = {
                    if (p.complete || onStep == null) onAdjust(id!!) else {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onStep(id!!, quickSteps(p.habit.goal).first())
                    }
                },
                onLongClick = { onAdjust(id!!) },
            )
            .padding(horizontal = if (large) 24.dp else 0.dp, vertical = if (large) 12.dp else 6.dp)
            .testTag(if (large) "today_habit" else "today_checkin"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProgressDot(p, if (large) 22.dp else 18.dp)
        Spacer(Modifier.width(if (large) 14.dp else 10.dp))
        Text(
            p.habit.name,
            fontSize = if (large) 16.sp else 15.sp,
            color = if (p.complete || !p.editable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = if (large) Modifier.weight(1f) else Modifier,
        )
        p.progressText?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProgressDot(p: HabitProgress, size: Dp = 22.dp) =
    ProgressRing(fraction = p.fraction, complete = p.complete, size = size, stroke = 2.dp, checkSize = size * 0.68f)

/** Empty ring → partial arc → filled check, animated between states. */
@Composable
private fun ProgressRing(fraction: Float, complete: Boolean, size: Dp, stroke: Dp, checkSize: Dp, dimmed: Boolean = false) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (dimmed) 0.6f else 1f)
    val arc by animateFloatAsState(fraction, tween(350), label = "arc")
    val fill by animateFloatAsState(if (complete) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "fill")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(track, 0f, 360f, false, Offset(w / 2, w / 2), arcSize, style = Stroke(w))
            if (arc > 0f) drawArc(primary, -90f, 360f * arc, false, Offset(w / 2, w / 2), arcSize, style = Stroke(w, cap = StrokeCap.Round))
            // The fill pops in with a little overshoot when a habit is completed.
            if (fill > 0f) drawCircle(primary, radius = this.size.minDimension / 2 * fill.coerceAtMost(1.08f))
        }
        // Always composed; the animation only touches the layer, so the ring does not recompose per frame.
        Icon(
            Icons.Filled.Check, null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(checkSize).graphicsLayer {
                val v = if (fill > 0.3f) fill else 0f
                scaleX = v; scaleY = v; alpha = if (v > 0f) 1f else 0f
            },
        )
    }
}

/**
 * Set today's total for one habit, opened in place under its row on
 * [HabitsPage]. Counted habits get −/+, a number field and quick-add chips; a
 * tick-box habit offers undo (or done). [onEdit] leads to the habit itself
 * (name, goal, days); null for one deleted since it was checked in today.
 */
@Composable
private fun HabitAdjustPanel(p: HabitProgress, onSet: (Int) -> Unit, onBack: () -> Unit, onEdit: (() -> Unit)?) {
    val target = p.habit.target
    // Whole value selected, so typing replaces it instead of appending ("030").
    var field by remember(p.habit.id) { mutableStateOf(TextFieldValue(p.done.toString(), TextRange(0, p.done.toString().length))) }
    val value = field.text.toIntOrNull()
    val set: (Int) -> Unit = { n -> val t = n.coerceAtLeast(0).toString(); field = TextFieldValue(t, TextRange(t.length)) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
            .testTag("today_habit_adjust"),
    ) {
        if (target == null) {
            Text(stringResource(if (p.done > 0) R.string.today_habits_tick_body else R.string.today_habits_tick_do), fontSize = 14.sp)
        } else {
            Text(stringResource(R.string.today_habits_adjust_body), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { set((value ?: 0) - 1) }) { Icon(Icons.Outlined.Remove, null) }
                OutlinedTextField(
                    value = field,
                    onValueChange = { v -> field = v.copy(text = v.text.filter(Char::isDigit).take(6)) },
                    singleLine = true,
                    suffix = { Text("/ ${p.habit.goalText}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("today_habit_count"),
                )
                IconButton(onClick = { set((value ?: 0) + 1) }) { Icon(Icons.Outlined.Add, null) }
            }
            // One set at a time: "+10" after each set beats typing a running total.
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (step in quickSteps(target)) {
                    SuggestionChip(onClick = { set((value ?: 0) + step) }, label = { Text("+$step") })
                }
                SuggestionChip(onClick = { set(target) }, label = { Text(stringResource(R.string.today_habits_fill)) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onEdit != null) {
                TextButton(onClick = onEdit, modifier = Modifier.testTag("today_habit_edit")) { Text(stringResource(R.string.today_habits_edit)) }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text(stringResource(R.string.today_cancel)) }
            when {
                target != null -> TextButton(enabled = value != null, onClick = { onSet(value ?: 0) }, modifier = Modifier.testTag("today_habit_adjust_save")) {
                    Text(stringResource(R.string.today_save))
                }
                p.done > 0 -> TextButton(onClick = { onSet(0) }) {
                    Text(stringResource(R.string.today_habits_undo), color = MaterialTheme.colorScheme.error)
                }
                else -> TextButton(onClick = { onSet(1) }) { Text(stringResource(R.string.today_done)) }
            }
        }
    }
}

/**
 * Coming from a number field, some IMEs (seen with Doubao) keep showing the
 * digit pad when focus lands on a text field: they ignore the restartInput
 * Compose sends while the keyboard stays up. The number field marks that it
 * was left ([leftNumberField]); the text field that takes focus next briefly
 * hides and re-shows the keyboard ([reopenKeyboardAfterNumber]), which makes
 * every IME read the new input type. Other focus moves (opening the dialog)
 * leave the keyboard alone.
 */
private class NumberFieldExit {
    var focused = false
    var pending = false
}

private fun Modifier.leftNumberField(exit: NumberFieldExit): Modifier = onFocusChanged { state ->
    if (exit.focused && !state.isFocused) exit.pending = true
    exit.focused = state.isFocused
}

private fun Modifier.reopenKeyboardAfterNumber(exit: NumberFieldExit): Modifier = composed {
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    onFocusChanged { state ->
        if (state.isFocused && exit.pending) {
            exit.pending = false
            scope.launch {
                keyboard?.hide()
                delay(60)
                keyboard?.show()
            }
        }
    }
}

/** Quick-add steps sized to the goal: 20 push-ups → +5 +10, 5 km → +1 +2. */
private fun quickSteps(target: Int): List<Int> = when {
    target >= 40 -> listOf(10, 20)
    target >= 10 -> listOf(5, 10)
    target >= 4 -> listOf(1, 2)
    else -> listOf(1)
}

/**
 * Daily check-ins, one page for both doing and managing them (§14.3,
 * 2026-10-15: the bottom sheet and the manager merged). Today's items on top —
 * tap records a set, long-press (or tap a done one) opens its adjust panel in
 * place, which also leads to editing the item; items not due today below;
 * the evening reminder last. Opened from the top-bar ring, the ⋯ menu, a
 * day's check-in block ([focusId] = that item's panel already open) and the
 * reminder notification. Back closes the editor, then the panel, then the page.
 *
 * No dialogs on the way (2026-10-15): deleting is undone from a snackbar, the
 * reminder time is set in place.
 */
@Composable
fun HabitsPage(
    today: List<HabitProgress>,
    habits: List<Habit>,
    dateLabel: String,
    focusId: String?,
    onStep: (habitId: String, by: Int) -> Unit,
    onSet: (Habit, Int) -> Unit,
    onAdd: (name: String, target: Int?, unit: String?, days: Set<DayOfWeek>?) -> Unit,
    onUpdate: (Habit, name: String, target: Int?, unit: String?, days: Set<DayOfWeek>?) -> Unit,
    onArchive: (Habit) -> Unit,
    onUnarchive: (Habit) -> Unit,
    reminderOn: Boolean,
    reminderMinute: Int,
    onReminder: (Boolean) -> Unit,
    onReminderTime: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var adjusting by remember { mutableStateOf(focusId) }
    // Which editor shows: null = the list; a Habit? inside = that habit, or a new one.
    var editor by remember { mutableStateOf<Editing?>(null) }
    val notToday = remember(today, habits) {
        val todayIds = today.mapNotNull { it.habit.id }.toSet()
        habits.filter { !it.archived && it.id != null && it.id !in todayIds }
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val undoLabel = stringResource(R.string.today_undo)
    // Lives here, not in the list: the list leaves composition while the editor shows,
    // and coming back from an edit must land where you were, not at the top.
    val listState = rememberLazyListState()
    // Opened from the timeline on one item: bring it into view, once (header is item 0).
    LaunchedEffect(Unit) {
        val i = today.indexOfFirst { it.habit.id == focusId }
        if (i > 2) listState.animateScrollToItem(i)
    }

    // Slides in like a page; on the way out it slides back before the window goes.
    // Set to true once; re-applying it on every recomposition would cancel the way out.
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val close = { shown.targetState = false }
    LaunchedEffect(shown.currentState, shown.isIdle) { if (shown.isIdle && !shown.currentState && !shown.targetState) onDismiss() }

    val delete: (Habit) -> Unit = { h ->
        onArchive(h)
        editor = null
        adjusting = null
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(context.getString(R.string.today_habits_deleted, h.name), actionLabel = undoLabel, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onUnarchive(h)
        }
    }

    Dialog(
        onDismissRequest = {
            when {
                editor != null -> editor = null
                adjusting != null -> adjusting = null
                else -> close()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val lightBg = MaterialTheme.colorScheme.background.luminance() > 0.5f
        LaunchedEffect(lightBg) {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                // The page draws its own entrance; no dimmed backdrop or window animation underneath.
                w.setDimAmount(0f)
                w.setWindowAnimations(0)
                WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = lightBg
            }
        }
        AnimatedVisibility(
            visibleState = shown,
            enter = slideInHorizontally(tween(260)) { it / 4 } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(200)) { it / 4 } + fadeOut(tween(180)),
        ) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag("today_habits")) {
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = editor,
                        transitionSpec = {
                            val forward = targetState != null
                            (slideInHorizontally(tween(240)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(200))) togetherWith
                                (slideOutHorizontally(tween(200)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(160)))
                        },
                        label = "habitsPage",
                    ) { editing ->
                        if (editing != null) {
                            val habit = editing.habit
                            HabitEditor(
                                habit = habit,
                                onSave = { name, target, unit, days ->
                                    if (habit == null) onAdd(name, target, unit, days) else onUpdate(habit, name, target, unit, days)
                                    editor = null
                                },
                                onDelete = habit?.let { h -> { delete(h) } },
                                onBack = { editor = null },
                            )
                        } else {
                            HabitsList(
                                today = today,
                                notToday = notToday,
                                dateLabel = dateLabel,
                                listState = listState,
                                adjusting = adjusting,
                                onAdjusting = { adjusting = it },
                                onStep = onStep,
                                onSet = onSet,
                                onEdit = { editor = Editing(it) },
                                reminderOn = reminderOn,
                                reminderMinute = reminderMinute,
                                onReminder = onReminder,
                                onReminderTime = onReminderTime,
                                onBack = close,
                            )
                        }
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))
                }
            }
        }
    }
}

/** How long an adjust panel takes to unroll. */
private const val PANEL_MS = 200

/** The editor showing: [habit] null = a new one. A class so "editing nothing" and "adding" differ. */
private data class Editing(val habit: Habit?)

@Composable
private fun HabitsList(
    today: List<HabitProgress>,
    notToday: List<Habit>,
    dateLabel: String,
    listState: LazyListState,
    adjusting: String?,
    onAdjusting: (String?) -> Unit,
    onStep: (habitId: String, by: Int) -> Unit,
    onSet: (Habit, Int) -> Unit,
    onEdit: (Habit?) -> Unit,
    reminderOn: Boolean,
    reminderMinute: Int,
    onReminder: (Boolean) -> Unit,
    onReminderTime: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val doneCount = today.count { it.complete }
    val empty = today.isEmpty() && notToday.isEmpty()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        PageBar(stringResource(R.string.today_habits_menu), onBack = onBack) {
            if (!empty) {
                TextButton(onClick = { onEdit(null) }, modifier = Modifier.testTag("today_habit_add")) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.today_habits_add))
                }
            }
        }
        if (empty) {
            HabitsEmpty(onAdd = { onEdit(null) })
            return@Column
        }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 88.dp)) {
            if (today.isNotEmpty()) {
                item(key = "header") {
                    val all = doneCount == today.size
                    Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.today_habits_today_label, dateLabel),
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (all) stringResource(R.string.today_habits_done_short) else stringResource(R.string.today_habits_progress, doneCount, today.size),
                            fontSize = 14.sp, fontWeight = if (all) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (all) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(today, key = { it.habit.id ?: it.habit.name }) { p ->
                    // No placement animation: rows below a panel must move with it, frame by frame,
                    // like the header, hint and reminder rows do — a lagging spring made them overlap.
                    Column(Modifier.animateItem(placementSpec = null)) {
                        CheckInRow(p, large = true, onStep = onStep, onAdjust = { onAdjusting(if (adjusting == it) null else it) })
                        val open = p.habit.id != null && p.habit.id == adjusting
                        val bring = remember { BringIntoViewRequester() }
                        // Opened near the bottom: once unrolled, show all of it.
                        LaunchedEffect(open) { if (open) { delay(PANEL_MS.toLong()); bring.bringIntoView() } }
                        AnimatedVisibility(
                            visible = open,
                            // Unrolls downward from under its row, like a drawer, and rolls back up.
                            enter = expandVertically(tween(PANEL_MS), expandFrom = Alignment.Top) + fadeIn(tween(PANEL_MS)),
                            exit = shrinkVertically(tween(160), shrinkTowards = Alignment.Top) + fadeOut(tween(100)),
                        ) {
                            Column(Modifier.bringIntoViewRequester(bring)) {
                                HabitAdjustPanel(
                                    p,
                                    onSet = { onSet(p.habit, it); onAdjusting(null) },
                                    onBack = { onAdjusting(null) },
                                    onEdit = if (p.habit.archived) null else ({ onEdit(p.habit) }),
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
                item(key = "hint") {
                    Text(
                        stringResource(R.string.today_habits_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
            if (notToday.isNotEmpty()) {
                item(key = "notTodayHeader") {
                    Text(
                        stringResource(R.string.today_habits_not_today), fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(notToday, key = { it.id!! }) { h ->
                    Row(
                        Modifier.animateItem(placementSpec = null).fillMaxWidth().clickable { onEdit(h) }
                            .padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
                            .testTag("today_habit_manage_item"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(h.name, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(habitDetail(h), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "reminder") {
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                ReminderRow(reminderOn, reminderMinute, onReminder = onReminder, onReminderTime = onReminderTime)
            }
        }
    }
}

/** Nothing to check in yet: say what this is, one clear way to start, and that Minis can help. */
@Composable
private fun HabitsEmpty(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ProgressRing(fraction = 0f, complete = false, size = 48.dp, stroke = 3.dp, checkSize = 32.dp, dimmed = true)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.today_habits_empty_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.today_habits_empty_body), fontSize = 14.sp,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onAdd, modifier = Modifier.testTag("today_habit_add")) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.today_habits_add))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.today_habits_empty_agent), fontSize = 13.sp,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Back arrow, title, and whatever goes on the right. */
@Composable
private fun PageBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        actions()
    }
}

/**
 * "Evening reminder · 20:00, only if something is left" (§14.6). Tapping the
 * row opens the time in place; the switch turns it on or off. When the system
 * would swallow the notification, a red line says so and leads to settings —
 * otherwise a reminder that never arrives looks like a broken feature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderRow(on: Boolean, minute: Int, onReminder: (Boolean) -> Unit, onReminderTime: (Int) -> Unit) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var canNotify by remember { mutableStateOf(HabitReminder.canNotify(context)) }
    // Back from system settings: look again.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { canNotify = HabitReminder.canNotify(context) }
    }
    val hm = "%02d:%02d".format(minute / 60, minute % 60)
    Column(Modifier.animateContentSize(tween(200))) {
        Row(
            Modifier.fillMaxWidth().clickable { picking = !picking }
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .testTag("today_habit_reminder"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.habit_reminder_setting), fontSize = 16.sp)
                Text(
                    if (on) stringResource(R.string.habit_reminder_setting_on, hm) else stringResource(R.string.habit_reminder_setting_off),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = on, onCheckedChange = { onReminder(it); if (!it) picking = false })
        }
        if (on && !canNotify) {
            Row(
                Modifier.fillMaxWidth().clickable { context.startActivity(HabitReminder.settingsIntent(context)) }
                    .padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.habit_reminder_blocked), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.habit_reminder_turn_on), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (picking) {
            val time = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = true)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
            ) {
                TimeInput(state = time)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { picking = false }) { Text(stringResource(R.string.today_cancel)) }
                    TextButton(
                        onClick = {
                            onReminderTime(time.hour * 60 + time.minute)
                            if (!on) onReminder(true)
                            picking = false
                        },
                        modifier = Modifier.testTag("today_habit_reminder_save"),
                    ) { Text(stringResource(R.string.today_save)) }
                }
            }
        }
    }
}

@Composable
private fun habitDetail(h: Habit): String {
    val locale = Locale.getDefault()
    val days = h.days?.sorted()?.joinToString(" ") { it.getDisplayName(TextStyle.SHORT, locale) }
        ?: stringResource(R.string.today_habits_every_day)
    return listOfNotNull(h.goalText, days).joinToString("  ·  ")
}

/**
 * Add or edit one habit, in place of the list on [HabitsPage]. Delete takes
 * effect at once; the page offers undo (no confirm dialog).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HabitEditor(
    habit: Habit?,
    onSave: (name: String, target: Int?, unit: String?, days: Set<DayOfWeek>?) -> Unit,
    onDelete: (() -> Unit)?,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(habit?.name.orEmpty()) }
    var target by remember { mutableStateOf(habit?.target?.toString().orEmpty()) }
    var unit by remember { mutableStateOf(habit?.unit.orEmpty()) }
    var days by remember { mutableStateOf(habit?.days ?: DayOfWeek.entries.toSet()) }
    // A new habit starts with the keyboard up on its name.
    val nameFocus = remember { FocusRequester() }
    val numberExit = remember { NumberFieldExit() }
    // Wait for the page's window to take focus first; an earlier request is dropped.
    val windowInfo = LocalWindowInfo.current
    if (habit == null) LaunchedEffect(Unit) {
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        nameFocus.requestFocus()
    }
    val locale = Locale.getDefault()
    // Weeks start on the locale's first day (Monday in China, Sunday in the US).
    val firstDay = java.time.temporal.WeekFields.of(locale).firstDayOfWeek
    val week = (0L until 7L).map { firstDay.plus(it) }
    val canSave = name.isNotBlank() && days.isNotEmpty()
    // Raw values; Habit.withDefinition normalises them.
    val save = { if (canSave) onSave(name, target.toIntOrNull(), unit, days) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        PageBar(stringResource(if (habit == null) R.string.today_habits_add else R.string.today_habits_edit), onBack = onBack) {
            TextButton(enabled = canSave, onClick = save, modifier = Modifier.testTag("today_habit_save")) {
                Text(stringResource(R.string.today_save))
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.replace('\n', ' ') },
                label = { Text(stringResource(R.string.today_habits_name)) },
                placeholder = { Text(stringResource(R.string.today_habits_name_example)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).reopenKeyboardAfterNumber(numberExit).testTag("today_habit_name"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { v -> target = v.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.today_habits_target)) },
                    supportingText = { Text(stringResource(R.string.today_habits_target_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1.6f).leftNumberField(numberExit).testTag("today_habit_target"),
                )
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it.replace('\n', ' ').take(8) },
                    label = { Text(stringResource(R.string.today_habits_unit)) },
                    placeholder = { Text(stringResource(R.string.today_habits_unit_example)) },
                    singleLine = true,
                    enabled = target.isNotEmpty(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.weight(1f).reopenKeyboardAfterNumber(numberExit).testTag("today_habit_unit"),
                )
            }
            Text(stringResource(R.string.today_habits_days), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in week) {
                    FilterChip(
                        selected = d in days,
                        onClick = { days = if (d in days) days - d else days + d },
                        label = { Text(d.getDisplayName(TextStyle.SHORT, locale)) },
                    )
                }
            }
            if (onDelete != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().testTag("today_habit_delete")) {
                    Text(stringResource(R.string.today_habits_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
