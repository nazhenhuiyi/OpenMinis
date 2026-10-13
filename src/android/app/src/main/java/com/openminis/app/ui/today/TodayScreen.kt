package com.openminis.app.ui.today

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.openminis.app.R
import com.openminis.app.ui.chat.InputCircleButton
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.data.photos.CameraPhoto
import com.openminis.app.data.photos.CameraRollRules
import com.openminis.app.data.photos.CameraRollSource
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a long-press acted on: a note, or a photo group. */
private sealed interface Target {
    data class Note(val note: TodayNote) : Target
    data class Group(val group: TodayGroup) : Target
}

/**
 * [T-today-tab] The Today tab (docs/design/today/SPEC.md §2): a timeline of
 * notes and photo groups, newest at the bottom next to the composer.
 *
 * [onAskMinis] opens a new chat with the given draft text and photos (§2.4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onAskMinis: (text: String, photoUris: List<String>) -> Unit,
    modifier: Modifier = Modifier,
    vm: TodayViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val draft by vm.draft.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val zone = ZoneId.systemDefault()

    // Reload on every resume: picks up journal edits made in Obsidian or the
    // shell while we were in the background, and photos taken meanwhile.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.onResumed() }
    }

    // ── Permissions (SPEC §7) ──
    val mediaLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.reload() }
    val photosLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it } && Build.VERSION.SDK_INT >= 29) {
            mediaLocationLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else {
            vm.reload()
        }
    }
    val requestPhotos = {
        photosLauncher.launch(
            when {
                Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
        )
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.onLocationPermissionResult(result.values.any { it })
    }
    // Weather alerts (SPEC §16.6); a refusal just means no system notification.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // ── Camera (SPEC §2.3, §5.3) ──
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(false)
        vm.finishCapture()
    }
    val launchCamera: () -> Unit = launch@{
        val p = vm.prepareCapture() ?: run {
            Toast.makeText(context, R.string.today_capture_failed, Toast.LENGTH_SHORT).show()
            return@launch
        }
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, p.captureUri)
            clipData = ClipData.newRawUri("", p.captureUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(true)
        runCatching { cameraLauncher.launch(intent) }.onFailure {
            com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(false)
            vm.finishCapture()
            Toast.makeText(context, R.string.today_capture_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) launchCamera()
    }
    val onCamera = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) launchCamera() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // The check-in sheet, and which habit's adjust panel it shows (null = the list).
    var sheetOpen by remember { mutableStateOf(false) }
    var sheetEditing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vm) {
        vm.eventFlow.collect { e ->
            when (e) {
                is TodayViewModel.Event.Toast -> Toast.makeText(context, e.res, Toast.LENGTH_SHORT).show()
                TodayViewModel.Event.NeedLocationPermission -> locationLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
                TodayViewModel.Event.NeedNotificationPermission ->
                    if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                TodayViewModel.Event.AllHabitsDone -> {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    Toast.makeText(context, R.string.today_habits_all_done, Toast.LENGTH_SHORT).show()
                    // Ticked off the last one from the list: let the ring fill, then put the sheet away.
                    if (sheetOpen && sheetEditing == null) {
                        kotlinx.coroutines.delay(700)
                        if (sheetEditing == null) sheetOpen = false
                    }
                }
            }
        }
    }

    var sheetTarget by remember { mutableStateOf<Target?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<TodayNote?>(null) }
    // Photos to show and which one was tapped.
    var viewer by remember { mutableStateOf<Pair<List<CameraPhoto>, Int>?>(null) }
    var showAlbums by remember { mutableStateOf(false) }
    var showHabits by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    // Reverse layout: index 0 is the newest row, at the bottom.
    val nearTop by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(nearTop, state.hasOlder) { if (nearTop && state.loaded) vm.loadOlder() }
    // A newly sent note scrolls into view.
    val newestKey = state.days.lastOrNull()?.rows?.lastOrNull()?.key
    LaunchedEffect(newestKey) { if (newestKey != null) listState.animateScrollToItem(0) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            TodayTopBar(
                today = state.today,
                recordLocation = state.recordLocation,
                onRecordLocation = vm::setRecordLocation,
                showWeather = state.showWeather,
                onShowWeather = vm::setShowWeather,
                weatherAlerts = state.weatherAlerts,
                onWeatherAlerts = vm::setWeatherAlerts,
                onAlbums = { showAlbums = true },
                onHabits = { showHabits = true },
                habits = state.todayHabits,
                // No habits at all → a ghost chip that leads to the manager (SPEC §14.3).
                ghostChip = state.habits.none { !it.archived },
                onHabitsChip = { ghost -> if (ghost) showHabits = true else { sheetEditing = null; sheetOpen = true } },
            )
            // Pinned under the header while it applies, so it is seen without scrolling (§16.5).
            state.weatherAlert?.let { WeatherAlertBanner(it, onDismiss = vm::dismissWeatherAlert) }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("today_timeline"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
            ) {
                for (day in state.days.asReversed()) {
                    // "Nothing yet" only when the day has neither notes nor any check-in done.
                    if (day.rows.isEmpty() && day.checkIns.isEmpty() && (day.date != state.today || state.todayHabits.none { it.done > 0 })) {
                        item(key = "empty:${day.date}") {
                            Text(
                                stringResource(R.string.today_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                        }
                    }
                    items(day.rows.asReversed(), key = { it.key }) { row ->
                        when (row) {
                            is TodayRow.Note -> NoteRow(
                                note = row.note,
                                zone = zone,
                                locating = row.note.entry.id in state.locating,
                                showPlace = state.recordLocation,
                                editing = editingId != null && editingId == row.note.entry.id,
                                onLongPress = { sheetTarget = Target.Note(row.note) },
                                onOpenPhotos = { i ->
                                    // Missing photos are not shown in the viewer; map the tapped
                                    // index onto the photos that are actually on this phone.
                                    val shown = row.note.photos.mapNotNull { it.second }
                                    val start = row.note.photos.take(i).count { it.second != null }
                                    if (shown.isNotEmpty()) viewer = shown to start.coerceAtMost(shown.lastIndex)
                                },
                                onSaveEdit = { text -> vm.edit(row.note, text); editingId = null },
                                onCancelEdit = { editingId = null },
                            )
                            is TodayRow.Group -> GroupRow(
                                group = row.group,
                                zone = zone,
                                onLongPress = { sheetTarget = Target.Group(row.group) },
                                onOpen = { i -> viewer = row.group.photos to i },
                            )
                        }
                    }
                    // Reverse layout: emitted just before the divider = the first thing under it (§14.3).
                    // Today: the live checklist plus read-only leftovers; past days: read-only.
                    val block = if (day.date == state.today) state.todayHabits + day.checkIns else day.checkIns
                    if (block.isNotEmpty()) item(key = "checkins:${day.date}") {
                        CheckInBlock(block, onAdjust = { sheetEditing = it; sheetOpen = true })
                    }
                    item(key = "day:${day.date}") { DayDivider(day.date, state.today, day.weather?.takeIf { state.showWeather }) }
                }
                when (state.access) {
                    CameraRollSource.Access.NONE -> if (state.loaded) item(key = "perm") { PermissionCard(onAllow = requestPhotos) }
                    CameraRollSource.Access.PARTIAL -> item(key = "partial") { PartialBanner(onManage = requestPhotos) }
                    CameraRollSource.Access.FULL -> Unit
                }
            }
            Composer(
                draft = draft,
                pending = state.pending,
                onDraft = vm::setDraft,
                onCamera = onCamera,
                onDiscard = vm::discardPending,
                onSend = vm::send,
            )
        }
    }

    // ── Long-press menu (SPEC §2.4) ──
    sheetTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = { sheetTarget = null }) {
            val close = { sheetTarget = null }
            SheetItem(Icons.Outlined.Forum, stringResource(R.string.today_menu_ask)) {
                close()
                val (text, uris) = askPayload(context, target, zone)
                onAskMinis(text, uris)
            }
            when (target) {
                is Target.Note -> {
                    val editable = target.note.editable
                    SheetItem(Icons.Outlined.Edit, stringResource(R.string.today_menu_edit), enabled = editable) {
                        close(); editingId = target.note.entry.id
                    }
                    SheetItem(Icons.Outlined.ContentCopy, stringResource(R.string.today_menu_copy)) {
                        close()
                        clipboard.setText(AnnotatedString(target.note.entry.body))
                        Toast.makeText(context, R.string.today_copied, Toast.LENGTH_SHORT).show()
                    }
                    SheetItem(Icons.Outlined.Delete, stringResource(R.string.today_menu_delete), enabled = editable, danger = true) {
                        close(); confirmDelete = target.note
                    }
                    if (!editable) {
                        Text(
                            stringResource(R.string.today_dup_disabled),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                }
                is Target.Group -> SheetItem(Icons.Outlined.Image, stringResource(R.string.today_menu_open_album)) {
                    close()
                    val uri = Uri.parse(target.group.photos.first().uri)
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    confirmDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.today_delete_title)) },
            text = { if (note.entry.photos.isNotEmpty()) Text(stringResource(R.string.today_delete_body)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(note); confirmDelete = null }) {
                    Text(stringResource(R.string.today_menu_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.today_cancel)) } },
        )
    }

    viewer?.let { (photos, start) -> PhotoViewer(photos, start, zone, onDismiss = { viewer = null }) }

    // ── Today's check-ins (SPEC §14.3) ──
    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            HabitsSheetContent(
                habits = state.todayHabits,
                editingId = sheetEditing,
                onEditingChange = { sheetEditing = it },
                onStep = vm::stepHabit,
                onSet = vm::setHabitDone,
                onManage = { sheetOpen = false; showHabits = true },
            )
        }
    }

    if (showHabits) {
        HabitsManagerDialog(
            habits = state.habits,
            onAdd = vm::addHabit,
            onUpdate = vm::updateHabit,
            onArchive = vm::archiveHabit,
            onDismiss = { showHabits = false },
        )
    }

    if (showAlbums) {
        AlbumsDialog(vm = vm, selected = state.includedAlbums, onDone = { vm.setIncludedAlbums(it); showAlbums = false })
    }
}

private fun askPayload(context: android.content.Context, target: Target, zone: ZoneId): Pair<String, List<String>> {
    // Full date and the entry id: the chat must be able to tell which entry
    // this is (an older day's note would otherwise be looked for in today's).
    val hm = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    return when (target) {
        is Target.Note -> {
            val at = Instant.ofEpochMilli(target.note.atMs).atZone(zone)
            val quote = target.note.entry.body.lines().joinToString("\n") { "> $it" }
            context.getString(R.string.today_ask_quote_note, hm.format(at), target.note.entry.id.orEmpty()) + "\n" + quote + "\n\n" to
                target.note.photos.mapNotNull { it.second?.uri }
        }
        is Target.Group -> {
            val at = Instant.ofEpochMilli(target.group.photos.first().takenAtMs).atZone(zone)
            context.getString(R.string.today_ask_quote_photos, hm.format(at)) + "\n\n" to target.group.photos.map { it.uri }
        }
    }
}

// ───────────────────────────── Pieces ─────────────────────────────

@Composable
private fun TodayTopBar(
    today: LocalDate,
    recordLocation: Boolean,
    onRecordLocation: (Boolean) -> Unit,
    showWeather: Boolean,
    onShowWeather: (Boolean) -> Unit,
    weatherAlerts: Boolean,
    onWeatherAlerts: (Boolean) -> Unit,
    onAlbums: () -> Unit,
    onHabits: () -> Unit,
    habits: List<com.openminis.app.data.journal.HabitProgress>,
    ghostChip: Boolean,
    onHabitsChip: (ghost: Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.tab_today), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(dayLabel(today), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (habits.isNotEmpty() || ghostChip) HabitsChip(habits, onClick = { onHabitsChip(habits.isEmpty()) })
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, contentDescription = null) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.today_settings_location)) },
                    trailingIcon = { Switch(checked = recordLocation, onCheckedChange = null) },
                    onClick = { onRecordLocation(!recordLocation) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.today_settings_weather)) },
                    trailingIcon = { Switch(checked = showWeather, onCheckedChange = null) },
                    onClick = { onShowWeather(!showWeather) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.today_settings_weather_alerts)) },
                    trailingIcon = { Switch(checked = weatherAlerts, onCheckedChange = null) },
                    onClick = { onWeatherAlerts(!weatherAlerts) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.today_habits_menu)) },
                    onClick = { menu = false; onHabits() },
                    modifier = Modifier.testTag("today_menu_habits"),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.today_settings_albums)) },
                    onClick = { menu = false; onAlbums() },
                )
            }
        }
    }
}

private fun dayLabel(d: LocalDate): String {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMdEEE"), locale).format(d)
}

@Composable
private fun DayDivider(date: LocalDate, today: LocalDate, weather: com.openminis.app.data.journal.DayWeather?) {
    val day = when (date) {
        today -> stringResource(R.string.today_divider_today, dayLabel(date))
        today.minusDays(1) -> stringResource(R.string.today_divider_yesterday, dayLabel(date))
        else -> dayLabel(date)
    }
    // The page header of a diary: "10月12日周一 · 🌦 小雨 18–23°" (SPEC §15.4).
    val label = if (weather == null) day else
        "$day · " + weather.summary(stringResource(com.openminis.app.data.journal.TodayWeather.label(weather.code)))
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            // Unweighted: measured first at its natural width; the rules share what is left.
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/**
 * One timeline entry, journal style (SPEC §2.1): a quiet caption line —
 * time · place · edited — above the content. No rail, no dots, and text is
 * not boxed in a card, so the day reads like a page rather than a feed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineRow(
    atMs: Long,
    zone: ZoneId,
    place: String?,
    edited: Boolean,
    locating: Boolean,
    onLongPress: () -> Unit,
    onClick: (() -> Unit)? = null,
    tag: String,
    byMinis: Boolean = false,
    content: @Composable () -> Unit,
) {
    val time = remember(atMs) { DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(atMs).atZone(zone)) }
    val editedLabel = stringResource(R.string.today_edited)
    // Words the agent wrote or last changed say so (SPEC §4.6); "edited" is implied.
    val byMinisLabel = stringResource(R.string.today_by_minis)
    val caption = listOfNotNull(time, place, byMinisLabel.takeIf { byMinis }, editedLabel.takeIf { edited && !byMinis })
        .joinToString("  ·  ")
    Column(
        Modifier.fillMaxWidth().padding(bottom = 20.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongPress)
            .testTag(tag),
    ) {
        Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            MetaText(caption)
            if (locating) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(9.dp), strokeWidth = 1.2.dp, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(4.dp))
                MetaText(stringResource(R.string.today_locating))
            }
        }
        content()
    }
}

@Composable
private fun NoteRow(
    note: TodayNote,
    zone: ZoneId,
    locating: Boolean,
    showPlace: Boolean,
    editing: Boolean,
    onLongPress: () -> Unit,
    onOpenPhotos: (Int) -> Unit,
    onSaveEdit: (String) -> Unit,
    onCancelEdit: () -> Unit,
) {
    TimelineRow(
        atMs = note.atMs,
        zone = zone,
        place = note.entry.place?.takeIf { showPlace },
        edited = note.entry.edited != null,
        locating = locating,
        onLongPress = onLongPress,
        tag = "today_note",
        byMinis = note.entry.writtenByMinis,
    ) {
        if (editing) {
            var text by rememberSaveable(note.entry.id) { mutableStateOf(note.entry.body) }
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().testTag("today_edit_field"),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancelEdit) { Text(stringResource(R.string.today_cancel)) }
                TextButton(onClick = { onSaveEdit(text) }) { Text(stringResource(R.string.today_save)) }
            }
        } else {
            Text(note.entry.body, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp))
        }
        if (note.photos.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            PhotoGrid(note.photos.map { it.second }, onClick = onOpenPhotos)
        }
    }
}

/** Today's live weather alert (§16.5): a soft tinted note under the header, one line per alert; × hides it. */
@Composable
private fun WeatherAlertBanner(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 6.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
            .testTag("today_weather_alert"),
    ) {
        Text(
            text,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp).testTag("today_weather_alert_close")) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.common_close),
                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun MetaText(s: String) =
    Text(s, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))

@Composable
private fun GroupRow(group: TodayGroup, zone: ZoneId, onLongPress: () -> Unit, onOpen: (Int) -> Unit) {
    // No "N photos" header (SPEC §2.5): thumbnails speak for themselves.
    TimelineRow(
        atMs = group.photos.first().takenAtMs,
        zone = zone,
        place = group.place,
        edited = false,
        locating = false,
        onLongPress = onLongPress,
        onClick = { onOpen(0) },
        tag = "today_group",
    ) {
        PhotoGrid(group.photos, onClick = onOpen)
    }
}

/** 1 → one large image; 2–4 → 2×2; 5+ → three and "+N" (SPEC §2.5). null = not on this phone. */
@Composable
private fun PhotoGrid(photos: List<CameraPhoto?>, onClick: (Int) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    if (photos.size == 1) {
        // A missing photo keeps only a slim placeholder, not a 4:3 empty frame.
        val size = if (photos[0] == null) Modifier.fillMaxWidth().height(56.dp) else Modifier.fillMaxWidth().aspectRatio(4f / 3f)
        Thumb(photos[0], size.clip(shape), { onClick(0) }, big = true)
        return
    }
    val cells = if (photos.size > 4) photos.take(4) else photos
    Column(Modifier.clip(shape), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cells.chunked(2).forEachIndexed { r, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pair.forEachIndexed { c, p ->
                    val i = r * 2 + c
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        Thumb(p, Modifier.fillMaxSize(), { onClick(i) })
                        if (photos.size > 4 && i == 3) {
                            Box(Modifier.fillMaxSize().background(Color(0x73000000)), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.today_more_photos, photos.size - 3), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Thumb(photo: CameraPhoto?, modifier: Modifier, onClick: () -> Unit, big: Boolean = false) {
    if (photo == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant).testTag("today_photo_missing"), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.today_photo_missing),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(6.dp),
            )
        }
        return
    }
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(Uri.parse(photo.uri)).size(if (big) 900 else 360).crossfade(true).build(),
        contentDescription = photo.displayName,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick).testTag("today_thumb"),
    )
}

@Composable
private fun PermissionCard(onAllow: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp).testTag("today_perm_card"),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.today_perm_title), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.today_perm_body), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            FilledIconButtonText(stringResource(R.string.today_perm_allow), onAllow)
        }
    }
}

@Composable
private fun FilledIconButtonText(label: String, onClick: () -> Unit) {
    androidx.compose.material3.Button(onClick = onClick) { Text(label) }
}

@Composable
private fun PartialBanner(onManage: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.today_partial_banner), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.today_partial_manage),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onManage),
        )
    }
}

/**
 * Composer modelled on the chat input box (SPEC §2.2): one rounded card with
 * the text on top and a button row inside it — camera on the left, send on
 * the right. Built for longer writing: grows to 10 lines, Return inserts a
 * newline, and only the send button sends.
 */
@Composable
private fun Composer(
    draft: String,
    pending: List<PendingPhoto>,
    onDraft: (String) -> Unit,
    onCamera: () -> Unit,
    onDiscard: (PendingPhoto) -> Unit,
    onSend: () -> Unit,
) {
    val canSend = draft.isNotBlank() || pending.isNotEmpty()
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Surface(
            shape = shape,
            color = ChatColors.inputBg,
            shadowElevation = 2.dp,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, ChatColors.inputBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                if (pending.isNotEmpty()) {
                    LazyRow(
                        Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp).testTag("today_pending"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(pending, key = { it.uri }) { p ->
                            Box(Modifier.size(64.dp)) {
                                AsyncImage(
                                    model = Uri.parse(p.uri),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                                Box(
                                    Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(20.dp).clip(CircleShape)
                                        .background(Color(0xCC222222)).clickable { onDiscard(p) }.testTag("today_pending_remove"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.today_remove_photo),
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                TextField(
                    value = draft,
                    onValueChange = onDraft,
                    placeholder = { Text(stringResource(R.string.today_input_hint), fontSize = 16.sp) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
                    minLines = 1,
                    maxLines = 10,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("today_input"),
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InputCircleButton(onClick = onCamera) {
                        Icon(
                            Icons.Outlined.CameraAlt,
                            contentDescription = stringResource(R.string.today_take_photo),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(38.dp)
                            .background(if (canSend) ChatColors.sendButton else ChatColors.sendButtonDisabled, CircleShape)
                            .clip(CircleShape)
                            .clickable(enabled = canSend, onClick = onSend)
                            .testTag("today_send"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            contentDescription = stringResource(R.string.today_send),
                            tint = if (canSend) ChatColors.background else ChatColors.primaryText.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = { Icon(icon, null, tint = color) },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

/**
 * Full-screen viewer that opens straight on the tapped photo; swipe for the
 * others in the same entry (SPEC §2.5). No intermediate grid.
 */
@Composable
private fun PhotoViewer(photos: List<CameraPhoto>, start: Int, zone: ZoneId, onDismiss: () -> Unit) {
    // Draw behind the system bars so the black backdrop reaches the screen edges.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                val pager = rememberPagerState(initialPage = start.coerceIn(0, photos.lastIndex)) { photos.size }
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, null, tint = Color.White) }
                    if (photos.size > 1) {
                        Text(stringResource(R.string.today_viewer_counter, pager.currentPage + 1, photos.size), color = Color.White)
                    }
                }
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).testTag("today_viewer_pager")) { page ->
                    AsyncImage(
                        model = Uri.parse(photos[page].uri),
                        contentDescription = photos[page].displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                val p = photos[pager.currentPage]
                Text(
                    DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(p.takenAtMs).atZone(zone)) + "  ·  " + p.relativePath,
                    color = Color(0xFFBBBBBB),
                    fontSize = 12.sp,
                    modifier = Modifier.navigationBarsPadding().padding(16.dp),
                )
            }
        }
    }
}

/** "Included albums" (SPEC §5.1): camera folders are always in; others opt in. */
@Composable
private fun AlbumsDialog(vm: TodayViewModel, selected: Set<String>, onDone: (Set<String>) -> Unit) {
    var albums by remember { mutableStateOf<List<CameraRollSource.Album>?>(null) }
    var picked by remember { mutableStateOf(selected) }
    LaunchedEffect(Unit) { albums = vm.albums() }
    AlertDialog(
        onDismissRequest = { onDone(picked) },
        title = { Text(stringResource(R.string.today_settings_albums)) },
        text = {
            Column {
                Text(stringResource(R.string.today_albums_body), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                val list = albums
                if (list == null) {
                    CircularProgressIndicator(Modifier.padding(16.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(list, key = { it.bucketId }) { a ->
                            val always = CameraRollRules.classify(a.relativeDir, null, emptySet()) != null
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !always) {
                                    picked = if (a.bucketId in picked) picked - a.bucketId else picked + a.bucketId
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = always || a.bucketId in picked, onCheckedChange = null, enabled = !always)
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(a.name)
                                    Text("${a.relativeDir} · ${a.count}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(picked) }) { Text(stringResource(R.string.today_done)) } },
    )
}
