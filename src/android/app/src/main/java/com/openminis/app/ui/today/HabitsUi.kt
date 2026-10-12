package com.openminis.app.ui.today

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.rememberSheetScrollGuard
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
 * [HabitsSheetContent]. Takes no room from the timeline.
 *
 * Empty [habits] = the user has no check-ins yet: a faint "打卡" ghost that
 * leads to the manager, so the feature can be found without the ⋯ menu.
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
 * The check-in list shown in a bottom sheet. Rows behave as in
 * [CheckInBlock]. A non-null [editingId] shows that habit's adjust panel in
 * place — no dialog stacks on the sheet. The sheet stays open so progress is
 * visible as you tick.
 */
@Composable
fun HabitsSheetContent(
    habits: List<HabitProgress>,
    editingId: String?,
    onEditingChange: (String?) -> Unit,
    onComplete: (String) -> Unit,
    onSet: (Habit, Int) -> Unit,
    onManage: () -> Unit,
) {
    val editing = habits.firstOrNull { it.habit.id != null && it.habit.id == editingId }
    if (editing != null) {
        HabitAdjustPanel(editing, onSet = { onSet(editing.habit, it); onEditingChange(null) }, onBack = { onEditingChange(null) })
        return
    }
    val doneCount = habits.count { it.complete }
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp).testTag("today_habits")) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.today_habits_title), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.today_habits_progress, doneCount, habits.size),
                fontSize = 14.sp,
                color = if (doneCount == habits.size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onManage) { Text(stringResource(R.string.today_habits_manage)) }
        }
        // A fling at the top of the list must not carry on into the sheet and close it.
        LazyColumn(Modifier.heightIn(max = 420.dp).nestedScroll(rememberSheetScrollGuard())) {
            items(habits, key = { it.habit.id ?: it.habit.name }) { p ->
                CheckInRow(p, large = true, onComplete = onComplete, onAdjust = { onEditingChange(it) })
            }
        }
        Text(
            stringResource(R.string.today_habits_hint),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

/**
 * A day's check-ins as one block at the top of the day (§14.3): a quiet
 * "打卡 2/3" caption over one line per habit. Everything listed is counted,
 * so the caption always matches what you see. Tap and long-press both open
 * the adjust panel — in the timeline nothing is completed by a single touch;
 * the top-bar sheet is the quick way.
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
        for (p in items) CheckInRow(p, large = false, onComplete = null, onAdjust = onAdjust)
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
private fun CheckInRow(p: HabitProgress, large: Boolean, onComplete: ((String) -> Unit)?, onAdjust: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val id = p.habit.id
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                enabled = p.editable && id != null,
                onClick = {
                    if (p.complete || onComplete == null) onAdjust(id!!) else {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onComplete(id!!)
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
 * Set today's total for one habit, inside the sheet. Counted habits get −/+,
 * a number field and quick-add chips; a tick-box habit offers undo (or done).
 * The system back gesture returns to the list.
 */
@Composable
private fun HabitAdjustPanel(p: HabitProgress, onSet: (Int) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val target = p.habit.target
    // Whole value selected, so typing replaces it instead of appending ("030").
    var field by remember(p.habit.id) { mutableStateOf(TextFieldValue(p.done.toString(), TextRange(0, p.done.toString().length))) }
    val value = field.text.toIntOrNull()
    val set: (Int) -> Unit = { n -> val t = n.coerceAtLeast(0).toString(); field = TextFieldValue(t, TextRange(t.length)) }
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp).testTag("today_habit_adjust")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressDot(p)
            Spacer(Modifier.width(12.dp))
            Text(p.habit.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        Spacer(Modifier.height(12.dp))
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
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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

/** ⋯ → Daily check-ins: the list of habits; tap one to edit, + to add. */
@Composable
fun HabitsManagerDialog(
    habits: List<Habit>,
    onAdd: (name: String, target: Int?, unit: String?, days: Set<DayOfWeek>?) -> Unit,
    onUpdate: (Habit, name: String, target: Int?, unit: String?, days: Set<DayOfWeek>?) -> Unit,
    onArchive: (Habit) -> Unit,
    onDismiss: () -> Unit,
) {
    var editorOpen by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Habit?>(null) } // null = a new habit
    val active = habits.filter { !it.archived && it.id != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.today_habits_menu)) },
        text = {
            if (active.isEmpty()) {
                Text(stringResource(R.string.today_habits_manage_empty), fontSize = 14.sp)
            } else {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(active, key = { it.id!! }) { h ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { editTarget = h; editorOpen = true }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                                .testTag("today_habit_manage_item"),
                        ) {
                            Text(h.name, fontSize = 15.sp)
                            Text(habitDetail(h), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.today_done)) } },
        dismissButton = {
            TextButton(onClick = { editTarget = null; editorOpen = true }, modifier = Modifier.testTag("today_habit_add")) {
                Text(stringResource(R.string.today_habits_add))
            }
        },
    )
    if (editorOpen) {
        val habit = editTarget
        HabitEditDialog(
            habit = habit,
            onSave = { name, target, unit, days ->
                if (habit == null) onAdd(name, target, unit, days) else onUpdate(habit, name, target, unit, days)
                editorOpen = false
            },
            onDelete = habit?.let { h -> { onArchive(h); editorOpen = false } },
            onDismiss = { editorOpen = false },
        )
    }
}

@Composable
private fun habitDetail(h: Habit): String {
    val locale = Locale.getDefault()
    val days = h.days?.sorted()?.joinToString(" ") { it.getDisplayName(TextStyle.SHORT, locale) }
        ?: stringResource(R.string.today_habits_every_day)
    return listOfNotNull(h.goalText, days).joinToString("  ·  ")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HabitEditDialog(
    habit: Habit?,
    onSave: (name: String, target: Int?, unit: String?, days: Set<DayOfWeek>?) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(habit?.name.orEmpty()) }
    var target by remember { mutableStateOf(habit?.target?.toString().orEmpty()) }
    var unit by remember { mutableStateOf(habit?.unit.orEmpty()) }
    var days by remember { mutableStateOf(habit?.days ?: DayOfWeek.entries.toSet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // A new habit starts with the keyboard up on its name.
    val nameFocus = remember { FocusRequester() }
    val numberExit = remember { NumberFieldExit() }
    // Wait for the dialog's window to take focus first; an earlier request is dropped.
    val windowInfo = LocalWindowInfo.current
    if (habit == null) LaunchedEffect(Unit) {
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        nameFocus.requestFocus()
    }
    val locale = Locale.getDefault()
    // Weeks start on the locale's first day (Monday in China, Sunday in the US).
    val firstDay = java.time.temporal.WeekFields.of(locale).firstDayOfWeek
    val week = (0L until 7L).map { firstDay.plus(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (habit == null) R.string.today_habits_add else R.string.today_habits_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace('\n', ' ') },
                    label = { Text(stringResource(R.string.today_habits_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).reopenKeyboardAfterNumber(numberExit).testTag("today_habit_name"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { v -> target = v.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.today_habits_target)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1.6f).leftNumberField(numberExit).testTag("today_habit_target"),
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it.replace('\n', ' ').take(8) },
                        label = { Text(stringResource(R.string.today_habits_unit)) },
                        singleLine = true,
                        enabled = target.isNotEmpty(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.today_habits_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && days.isNotEmpty(),
                // Raw values; Habit.withDefinition normalises them.
                onClick = { onSave(name, target.toIntOrNull(), unit, days) },
                modifier = Modifier.testTag("today_habit_save"),
            ) { Text(stringResource(R.string.today_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.today_cancel)) } },
    )
    if (confirmDelete && onDelete != null) {
        MinisAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = stringResource(R.string.today_habits_delete_title),
            text = stringResource(R.string.today_habits_delete_body),
            confirmText = stringResource(R.string.today_habits_delete),
            onConfirm = { confirmDelete = false; onDelete() },
            isDestructive = true,
        )
    }
}
