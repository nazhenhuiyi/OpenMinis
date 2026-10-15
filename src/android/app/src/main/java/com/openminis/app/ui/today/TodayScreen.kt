package com.openminis.app.ui.today

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.DateFormat
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Eco
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.openminis.app.R
import com.openminis.app.data.photos.CameraPhoto
import com.openminis.app.data.photos.CameraRollRules
import com.openminis.app.data.photos.CameraRollSource
import com.openminis.app.data.photos.VideoThumb
import com.openminis.app.data.photos.stills
import com.openminis.app.ui.chat.InputCircleButton
import com.openminis.app.ui.theme.ChatColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** At most this many items per pick from the library, like a photo post elsewhere. */
private const val MAX_PICK = 9

/** A timeline suggestion opened on its "What is it?" page: the photos it covers, the guess, and the whole group. */
private class WhatIs(val photos: List<CameraPhoto>, val suggestion: SpeciesSuggestion, val group: List<CameraPhoto>)

/** "Identify" (§17.3) on [photos], starting on [start] (null = where it suggests), without [rejected] — [undo] takes that no back. */
private class Identify(photos: List<CameraPhoto>, val start: CameraPhoto?, val rejected: String? = null, val undo: (() -> Unit)? = null) {
    /** Species ID looks at photos only: any video handed in is left out here, whoever opens it. */
    val photos = photos.stills
}

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
                Build.VERSION.SDK_INT >= 34 -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                )
                Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
        )
    }
    // Photos allowed before videos were shown: the same request once more, for videos (same group, usually no dialog).
    LaunchedEffect(state.access) { if (vm.askVideosOnce()) requestPhotos() }
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

    // ── Photos from the library (system picker: no permission needed) ──
    // Edited photos of the note being edited; null = not editing. Lives here so the picker can add to it.
    var editPhotos by remember { mutableStateOf<List<PendingPhoto>?>(null) }
    val composerPick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK), vm::addPicked)
    val editPick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val added = vm.importPicked(uris)
            editPhotos = editPhotos?.let { cur -> cur + added.filter { a -> cur.none { it.relativePath == a.relativePath } } }
        }
    }
    val photosAndVideos = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
    val composerFocus = remember { FocusRequester() }

    // The check-in page (§14.3) when open, and the habit whose adjust panel it opens on.
    var habitsPage by remember { mutableStateOf<HabitsPageOpen?>(null) }
    val openHabits: (String?) -> Unit = { focus -> habitsPage = HabitsPageOpen(focus) }
    // The "new to your collection" moment after recording a species (§17.2).
    var collected by remember { mutableStateOf<TodayViewModel.Event.Collected?>(null) }

    LaunchedEffect(vm) {
        vm.eventFlow.collect { e ->
            when (e) {
                is TodayViewModel.Event.Toast -> Toast.makeText(context, e.res, Toast.LENGTH_SHORT).show()
                TodayViewModel.Event.NeedLocationPermission -> locationLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
                TodayViewModel.Event.NeedNotificationPermission ->
                    if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                // The reveal does its own haptics, on the stamp.
                is TodayViewModel.Event.Collected -> collected = e
                TodayViewModel.Event.AllHabitsDone -> {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    Toast.makeText(context, R.string.today_habits_all_done, Toast.LENGTH_SHORT).show()
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
    // "What is it?" for a suggestion on the timeline (§17.3): its photos, the guess, and the group for "other possibilities".
    var whatIs by remember { mutableStateOf<WhatIs?>(null) }
    // "Identify" (§17.3): which photos, and which one to start on (null = the best-looking).
    var identifying by remember { mutableStateOf<Identify?>(null) }
    // Nature collection (§17.4): open, and optionally straight onto one species.
    var nature by remember { mutableStateOf<String?>(null) }
    var showNature by remember { mutableStateOf(false) }
    // Mood month (§19.2), opened on this day's month.
    var moodStart by remember { mutableStateOf<LocalDate?>(null) }
    // "Not this" and "remove" happen at once, with a moment to take them back.
    val snackbar = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.today_undo)
    val dismissedText = stringResource(R.string.today_species_dismissed)
    val removedText = stringResource(R.string.today_species_removed)
    val offerUndo: (String, () -> Unit) -> Unit = { text, undo ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(text, actionLabel = undoLabel, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) undo()
        }
    }

    LaunchedEffect(state.speciesReady) { if (state.speciesReady) vm.warmUpSpecies() }

    // The first day colours appear: one quiet line on what the dot is, with a way in (§19.2).
    val hasMood = state.dayMood.isNotEmpty()
    LaunchedEffect(hasMood) {
        if (!hasMood || !vm.moodHintDue()) return@LaunchedEffect
        vm.moodHintSeen()
        val r = snackbar.showSnackbar(
            context.getString(R.string.today_mood_hint_snack),
            actionLabel = context.getString(R.string.today_mood_hint_action),
            duration = SnackbarDuration.Long,
        )
        if (r == SnackbarResult.ActionPerformed) moodStart = state.today
    }

    // Tapped the check-in reminder (§14.6): the check-in page, once the day is loaded.
    val openHabitsRequest by MainTabs.openHabitsRequest.collectAsState()
    LaunchedEffect(openHabitsRequest, state.loaded) {
        if (!openHabitsRequest || !state.loaded) return@LaunchedEffect
        MainTabs.consumeOpenHabits()
        openHabits(null)
    }

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
      Box(Modifier.fillMaxSize()) {
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
                onHabits = { openHabits(null) },
                speciesCpu = if (state.speciesCpuOffered) state.speciesCpu else null,
                onSpeciesCpu = vm::setSpeciesCpu,
                habits = state.todayHabits,
                // No habits at all → a ghost chip that leads to the manager (SPEC §14.3).
                ghostChip = state.loaded && state.habits.none { !it.archived },
                murmurReady = state.murmurReady,
                moodColors = state.moodColors,
                onMoodColors = vm::setMoodColors,
                onMood = { moodStart = state.today },
                smartSuggest = state.smartSuggest,
                onSmartSuggest = vm::setSmartSuggest,
                cloudAvailable = state.cloudAvailable,
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
                                // Only the row being edited gets them: an edit doesn't recompose every note.
                                editPhotos = if (editingId == row.note.entry.id) editPhotos.orEmpty() else emptyList(),
                                onRemoveEditPhoto = { p -> editPhotos = editPhotos?.minus(p) },
                                onAddEditPhotos = { runCatching { editPick.launch(photosAndVideos) } },
                                onLongPress = { sheetTarget = Target.Note(row.note) },
                                onOpenPhotos = { i ->
                                    // Missing photos are not shown in the viewer; map the tapped
                                    // index onto the photos that are actually on this phone.
                                    val shown = row.note.photos.mapNotNull { it.second }
                                    val start = row.note.photos.take(i).count { it.second != null }
                                    if (shown.isNotEmpty()) viewer = shown to start.coerceAtMost(shown.lastIndex)
                                },
                                onSaveEdit = { text ->
                                    vm.edit(row.note, text, editPhotos.orEmpty().map { it.relativePath })
                                    editingId = null; editPhotos = null
                                },
                                onSpecies = { latin -> nature = latin; showNature = true },
                                tags = state.tags,
                                onCancelEdit = { editingId = null; editPhotos = null },
                                suggestion = row.note.entry.id?.let { state.murmurSuggestions[it] },
                                // Every action says what happened and can be taken back (§19.3).
                                onRemind = {
                                    val s = row.note.entry.id?.let { state.murmurSuggestions[it] }
                                    vm.acceptReminder(row.note)?.let { undo ->
                                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        offerUndo(context.getString(R.string.today_suggest_set_snack, s?.let { whenText(context, it.atMs) }.orEmpty()), undo)
                                    }
                                },
                                onDismissSuggestion = {
                                    val wasSet = row.note.entry.id?.let { state.murmurSuggestions[it] }?.state ==
                                        com.openminis.app.data.murmur.MurmurSuggestion.SCHEDULED
                                    vm.dismissMurmurSuggestion(row.note)?.let { undo ->
                                        offerUndo(context.getString(if (wasSet) R.string.today_suggest_cancelled else R.string.today_suggest_dismissed), undo)
                                    }
                                },
                                onRetime = { ms -> vm.setReminderTime(row.note, ms) },
                                moodWord = row.note.entry.id?.let { state.noteMood[it] },
                                onSetMood = { w -> row.note.entry.id?.let { vm.setNoteMood(row.note.date, it, w) } },
                                onAskSuggestion = { extra ->
                                    val s = row.note.entry.id?.let { state.murmurSuggestions[it] }
                                    val (text, uris) = askPayload(context, Target.Note(row.note), zone)
                                    val more = if (s?.type == com.openminis.app.data.murmur.MurmurSuggestion.TYPE_ANSWER)
                                        context.getString(R.string.today_suggest_follow_text, extra) else extra
                                    onAskMinis(text + more, uris)
                                },
                            )
                            is TodayRow.Group -> {
                                // One suggestion per kind found in the group, its photos together (§17.2).
                                val byKind = row.group.photos.mapNotNull { p -> state.suggestions[p.relativePath]?.let { p to it } }
                                    .groupBy { it.second.latin }.values.map { ps -> ps.map { it.first } to ps.first().second }
                                GroupRow(
                                    group = row.group,
                                    zone = zone,
                                    onLongPress = { sheetTarget = Target.Group(row.group) },
                                    onOpen = { i -> viewer = row.group.photos to i },
                                    tags = state.tags,
                                    onTag = { latin -> nature = latin; showNature = true },
                                    below = if (byKind.isEmpty()) null else {
                                        {
                                            byKind.forEach { (photos, sug) ->
                                                SpeciesSuggestionChip(
                                                    photo = photos.first(),
                                                    count = photos.size,
                                                    s = sug,
                                                    onOpen = { whatIs = WhatIs(photos, sug, row.group.photos) },
                                                    onDismiss = { offerUndo(dismissedText, vm.dismissSuggestions(photos)) },
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    // Reverse layout: emitted just before the divider = the first thing under it (§14.3).
                    // Today: the live checklist plus read-only leftovers; past days: read-only.
                    val block = if (day.date == state.today) state.todayHabits + day.checkIns else day.checkIns
                    if (block.isNotEmpty()) item(key = "checkins:${day.date}") {
                        CheckInBlock(block, onAdjust = { openHabits(it) })
                    }
                    item(key = "day:${day.date}") {
                        DayDivider(day.date, state.today, day.weather?.takeIf { state.showWeather }, state.dayMood[day.date], onMood = { moodStart = day.date })
                    }
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
                importing = state.importing,
                anchor = state.anchor,
                zone = zone,
                focus = composerFocus,
                onDraft = vm::setDraft,
                onCamera = onCamera,
                onPick = { runCatching { composerPick.launch(photosAndVideos) } },
                onDiscard = vm::discardPending,
                onClearAnchor = vm::clearAnchor,
                onSend = vm::send,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().padding(bottom = 104.dp))
        // Just recorded a species (§17.2): the card is dealt over everything.
        NewSpeciesReveal(
            event = collected,
            onOpen = { latin -> collected = null; nature = latin; showNature = true },
            onGone = { collected = null },
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
            val stills = when (target) {
                is Target.Note -> target.note.photos.mapNotNull { it.second }
                is Target.Group -> target.group.photos
            }.stills
            if (target is Target.Group) {
                SheetItem(Icons.Outlined.Edit, stringResource(R.string.today_menu_write_about)) {
                    close()
                    vm.writeAbout(target.group)
                    scope.launch { runCatching { composerFocus.requestFocus() } }
                }
            }
            if (state.speciesReady && stills.isNotEmpty()) {
                SheetItem(Icons.Outlined.Eco, stringResource(R.string.today_menu_identify)) {
                    close()
                    identifying = Identify(stills, null)
                }
            }
            when (target) {
                is Target.Note -> {
                    val editable = target.note.editable
                    SheetItem(Icons.Outlined.Edit, stringResource(R.string.today_menu_edit), enabled = editable) {
                        close(); editingId = target.note.entry.id
                        // One not on this phone keeps its place (no picture), so saving doesn't drop it.
                        editPhotos = target.note.photos.map { (path, p) -> p?.let(PendingPhoto::of) ?: PendingPhoto("", path) }
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
                    val first = target.group.photos.first()
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(first.uri), if (first.isVideo) "video/*" else "image/*")
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

    viewer?.let { (photos, start) ->
        PhotoViewer(
            photos, start, zone,
            onDismiss = { viewer = null },
            onIdentify = if (state.speciesReady) { p -> viewer = null; identifying = Identify(photos, p) } else null,
            tags = state.tags,
            onTag = { latin -> viewer = null; nature = latin; showNature = true },
        )
    }

    identifying?.let { id ->
        val askText = stringResource(R.string.today_identify_ask_text)
        IdentifySheet(
            vm = vm,
            photos = id.photos,
            start = id.start,
            tags = state.tags,
            rejected = id.rejected,
            onUndoReject = id.undo?.let { undo -> { undo(); identifying = null } },
            onPick = { photo, sug -> vm.record(listOf(photo), sug); identifying = null },
            onUntag = { photo -> identifying = null; vm.untag(photo)?.let { offerUndo(removedText, it) } },
            onAsk = { photo -> identifying = null; onAskMinis(askText, listOf(photo.uri)) },
            onDismiss = { identifying = null },
        )
    }

    whatIs?.let { w ->
        WhatIsItPage(
            vm = vm,
            photos = w.photos,
            s = w.suggestion,
            recorded = state.tags[w.photos.first().relativePath],
            onAdd = { vm.record(w.photos, w.suggestion); whatIs = null },
            onRemove = { whatIs = null; vm.untag(w.photos.first())?.let { offerUndo(removedText, it) } },
            // A real no: the suggestion goes, and the other candidates come up (with the undo, above the sheet).
            onNot = {
                whatIs = null
                identifying = Identify(w.group, w.photos.first(), rejected = w.suggestion.latin, undo = vm.dismissSuggestions(w.photos))
            },
            onOthers = { whatIs = null; identifying = Identify(w.group, w.photos.first()) },
            onDismiss = { whatIs = null },
        )
    }

    moodStart?.let { start -> MoodPage(vm, start, onDismiss = { moodStart = null }) }

    if (showNature) {
        NatureCollectionPage(
            vm = vm,
            open = nature,
            onAsk = { text, uris -> showNature = false; onAskMinis(text, uris) },
            onDismiss = { showNature = false },
        )
    }

    // ── Daily check-ins (SPEC §14.3): one page for checking in and managing ──
    habitsPage?.let { open ->
        HabitsPage(
            today = state.todayHabits,
            habits = state.habits,
            dateLabel = dayLabel(state.today),
            focusId = open.focusId,
            onStep = vm::stepHabit,
            onSet = vm::setHabitDone,
            onAdd = vm::addHabit,
            onUpdate = vm::updateHabit,
            onArchive = vm::archiveHabit,
            onUnarchive = vm::unarchiveHabit,
            reminderOn = state.habitReminder,
            reminderMinute = state.habitReminderMinute,
            onReminder = vm::setHabitReminder,
            onReminderTime = vm::setHabitReminderTime,
            onDismiss = { habitsPage = null },
        )
    }

    if (showAlbums) {
        AlbumsDialog(vm = vm, selected = state.includedAlbums, onDone = { vm.setIncludedAlbums(it); showAlbums = false })
    }
}

/** The check-in page is open; [focusId] = the habit whose adjust panel it opens on. */
private data class HabitsPageOpen(val focusId: String?)

private fun askPayload(context: android.content.Context, target: Target, zone: ZoneId): Pair<String, List<String>> {
    // Full date and the entry id: the chat must be able to tell which entry
    // this is (an older day's note would otherwise be looked for in today's).
    val hm = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    return when (target) {
        is Target.Note -> {
            val at = Instant.ofEpochMilli(target.note.atMs).atZone(zone)
            val quote = target.note.entry.body.lines().joinToString("\n") { "> $it" }
            context.getString(R.string.today_ask_quote_note, hm.format(at), target.note.entry.id.orEmpty()) + "\n" + quote + "\n\n" to
                target.note.photos.mapNotNull { it.second }.stills.map { it.uri }
        }
        is Target.Group -> {
            val at = Instant.ofEpochMilli(target.group.photos.first().takenAtMs).atZone(zone)
            context.getString(R.string.today_ask_quote_photos, hm.format(at)) + "\n\n" to target.group.photos.stills.map { it.uri }
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
    speciesCpu: Boolean?,
    onSpeciesCpu: (Boolean) -> Unit,
    habits: List<com.openminis.app.data.journal.HabitProgress>,
    ghostChip: Boolean,
    murmurReady: Boolean = false,
    moodColors: Boolean = true,
    onMoodColors: (Boolean) -> Unit = {},
    onMood: () -> Unit = {},
    smartSuggest: Boolean = false,
    onSmartSuggest: (Boolean) -> Unit = {},
    cloudAvailable: Boolean = true,
) {
    var menu by remember { mutableStateOf(false) }
    var askCpu by remember { mutableStateOf(false) }
    var askSuggest by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.tab_today), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(dayLabel(today), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (habits.isNotEmpty() || ghostChip) HabitsChip(habits, onClick = onHabits)
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
                // The 碎碎念 model is on this phone (§19).
                if (murmurReady) {
                    if (moodColors) DropdownMenuItem(
                        text = { Text(stringResource(R.string.today_mood_menu)) },
                        onClick = { menu = false; onMood() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.today_settings_mood_colors)) },
                        trailingIcon = { Switch(checked = moodColors, onCheckedChange = null) },
                        onClick = { onMoodColors(!moodColors) },
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.today_settings_smart_suggest))
                                if (!cloudAvailable && !smartSuggest) Text(
                                    stringResource(R.string.today_settings_smart_suggest_no_model),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        enabled = cloudAvailable || smartSuggest,
                        trailingIcon = { Switch(checked = smartSuggest, onCheckedChange = null, enabled = cloudAvailable || smartSuggest) },
                        // Off → on says first what leaves the phone (§19.3).
                        onClick = { if (smartSuggest) onSmartSuggest(false) else { menu = false; askSuggest = true } },
                    )
                }
                // Only on phones whose NPU can't run the model: the NPU always comes first (§17.1).
                if (speciesCpu != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.today_settings_species_cpu)) },
                        trailingIcon = { Switch(checked = speciesCpu, onCheckedChange = null) },
                        onClick = { if (speciesCpu) onSpeciesCpu(false) else { menu = false; askCpu = true } },
                        modifier = Modifier.testTag("today_menu_species_cpu"),
                    )
                }
            }
        }
    }
    if (askCpu) {
        AlertDialog(
            onDismissRequest = { askCpu = false },
            title = { Text(stringResource(R.string.today_species_cpu_title)) },
            text = { Text(stringResource(R.string.today_species_cpu_body)) },
            confirmButton = { TextButton(onClick = { askCpu = false; onSpeciesCpu(true) }) { Text(stringResource(R.string.today_species_cpu_on)) } },
            dismissButton = { TextButton(onClick = { askCpu = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (askSuggest) {
        AlertDialog(
            onDismissRequest = { askSuggest = false },
            title = { Text(stringResource(R.string.today_suggest_consent_title)) },
            text = { Text(stringResource(R.string.today_suggest_consent_body)) },
            confirmButton = { TextButton(onClick = { askSuggest = false; onSmartSuggest(true) }) { Text(stringResource(R.string.today_suggest_turn_on)) } },
            dismissButton = { TextButton(onClick = { askSuggest = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

internal fun dayLabel(d: LocalDate): String = localDate(d, "MMMdEEE")

/** "10月3日" / "Oct 3": a day without its weekday. */
internal fun shortDate(d: LocalDate): String = localDate(d, "MMMd")

private fun localDate(d: LocalDate, skeleton: String): String {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(d)
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/** "21:30" for a moment, in [zone]. */
internal fun clockTime(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    CLOCK.format(Instant.ofEpochMilli(atMs).atZone(zone))

@Composable
private fun DayDivider(
    date: LocalDate,
    today: LocalDate,
    weather: com.openminis.app.data.journal.DayWeather?,
    mood: com.openminis.app.data.murmur.MoodTier? = null,
    onMood: () -> Unit = {},
) {
    val day = when (date) {
        today -> stringResource(R.string.today_divider_today, dayLabel(date))
        today.minusDays(1) -> stringResource(R.string.today_divider_yesterday, dayLabel(date))
        else -> dayLabel(date)
    }
    // The page header of a diary: "10月12日周一 · 🌦 小雨 18–23°" (SPEC §15.4).
    val label = if (weather == null) day else
        "$day · " + weather.summary(stringResource(com.openminis.app.data.journal.TodayWeather.label(weather.code)))
    // The day's mood tints the rules on either side (§19.2): there, but nothing to read.
    val rule = mood?.soft(0.55f) ?: MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(rule))
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            // Unweighted: measured first at its natural width; the rules share what is left.
            modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(8.dp))
                .then(if (mood != null) Modifier.clickable(onClick = onMood) else Modifier)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(rule))
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
    mood: String? = null,
    onMood: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val time = remember(atMs) { clockTime(atMs, zone) }
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
            // The murmur's mood as a small icon after the caption, tap to change (§19.2).
            if (mood != null) {
                Spacer(Modifier.width(4.dp))
                MoodWord(mood, onMood)
            }
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
    editPhotos: List<PendingPhoto>,
    onRemoveEditPhoto: (PendingPhoto) -> Unit,
    onAddEditPhotos: () -> Unit,
    onLongPress: () -> Unit,
    onOpenPhotos: (Int) -> Unit,
    onSaveEdit: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSpecies: (String) -> Unit,
    tags: Map<String, com.openminis.app.data.species.PhotoTag> = emptyMap(),
    suggestion: com.openminis.app.data.murmur.MurmurSuggestion? = null,
    onRemind: () -> Unit = {},
    onDismissSuggestion: () -> Unit = {},
    onAskSuggestion: (String) -> Unit = {},
    onRetime: (Long) -> Unit = {},
    moodWord: String? = null,
    onSetMood: (String) -> Unit = {},
) {
    var pickingMood by remember(note.entry.id) { mutableStateOf(false) }
    TimelineRow(
        atMs = note.atMs,
        zone = zone,
        place = note.entry.place?.takeIf { showPlace },
        edited = note.entry.edited != null,
        locating = locating,
        onLongPress = onLongPress,
        tag = "today_note",
        byMinis = note.entry.writtenByMinis,
        mood = shownMood(moodWord).takeIf { !editing },
        onMood = { pickingMood = !pickingMood },
    ) {
        if (editing) {
            var text by rememberSaveable(note.entry.id) { mutableStateOf(note.entry.body) }
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().testTag("today_edit_field"),
            )
            // Photos come and go while editing; removing one only unhooks it, the library keeps it.
            Spacer(Modifier.height(8.dp))
            PendingStrip(editPhotos, onRemove = onRemoveEditPhoto, onAdd = onAddEditPhotos)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancelEdit) { Text(stringResource(R.string.today_cancel)) }
                TextButton(onClick = { onSaveEdit(text) }, enabled = text.isNotBlank() || editPhotos.isNotEmpty()) {
                    Text(stringResource(R.string.today_save))
                }
            }
            return@TimelineRow
        }
        val hasBody = note.entry.body.isNotBlank()
        if (hasBody) Text(note.entry.body, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp))
        if (note.photos.isNotEmpty()) {
            if (hasBody) Spacer(Modifier.height(10.dp))
            PhotoGrid(note.photos.map { it.second }, onClick = onOpenPhotos, tags = tags, onTag = onSpecies)
        }
        note.entry.species?.let { latin ->
            // The scientific name, quietly: the entry's words already say what it is (§17.2).
            Spacer(Modifier.height(6.dp))
            Text(
                "✦ " + listOfNotNull(note.entry.attrs[com.openminis.app.data.journal.JournalEntry.KEY_SPECIES_NAME], latin).distinct().joinToString(" · "),
                fontSize = 12.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Opens this species in the nature collection (§17.4).
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSpecies(latin) }.padding(vertical = 2.dp).testTag("today_note_species"),
            )
        }
        // A reminder, an answer or a nudge from 智能建议 (§19.3).
        androidx.compose.animation.AnimatedVisibility(
            pickingMood,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
        ) { MoodPicker(moodWord) { w -> pickingMood = false; onSetMood(w) } }
        suggestion?.let { MurmurSuggestionLine(it, onRemind = onRemind, onDismiss = onDismissSuggestion, onAsk = onAskSuggestion, onRetime = onRetime) }
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
internal fun MetaText(s: String) =
    Text(s, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))

@Composable
private fun GroupRow(
    group: TodayGroup,
    zone: ZoneId,
    onLongPress: () -> Unit,
    onOpen: (Int) -> Unit,
    tags: Map<String, com.openminis.app.data.species.PhotoTag> = emptyMap(),
    onTag: (String) -> Unit = {},
    below: (@Composable () -> Unit)? = null,
) {
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
        PhotoGrid(group.photos, onClick = onOpen, tags = tags, onTag = onTag)
        below?.invoke()
    }
}

/** 1 → one large image; 2–4 → 2×2; 5+ → three and "+N" (SPEC §2.5). null = not on this phone. */
@Composable
private fun PhotoGrid(
    photos: List<CameraPhoto?>,
    onClick: (Int) -> Unit,
    tags: Map<String, com.openminis.app.data.species.PhotoTag> = emptyMap(),
    onTag: (String) -> Unit = {},
) {
    val shape = RoundedCornerShape(10.dp)
    if (photos.size == 1) {
        // A missing photo keeps only a slim placeholder, not a 4:3 empty frame.
        val size = if (photos[0] == null) Modifier.fillMaxWidth().height(56.dp) else Modifier.fillMaxWidth().aspectRatio(4f / 3f)
        Box(size.clip(shape)) {
            Thumb(photos[0], Modifier.fillMaxSize(), { onClick(0) }, big = true)
            photos[0]?.let { p -> tags[p.relativePath]?.let { SpeciesTag(it, big = true, onClick = { onTag(it.latin) }, modifier = Modifier.align(Alignment.BottomStart)) } }
        }
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
                        val tag = p?.let { tags[it.relativePath] }
                        if (tag != null && !(photos.size > 4 && i == 3)) {
                            SpeciesTag(tag, big = false, onClick = { onTag(tag.latin) }, modifier = Modifier.align(Alignment.BottomStart))
                        }
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

/** The species recorded for a photo, as a pill on the photo (§17.2); tap → its page in the collection. */
@Composable
private fun SpeciesTag(
    tag: com.openminis.app.data.species.PhotoTag,
    big: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** On the black photo viewer: a light pill, no inset (on a photo it sits inset, dark). */
    onDark: Boolean = false,
) {
    Row(
        modifier.padding(if (onDark) 0.dp else if (big) 10.dp else 6.dp).clip(RoundedCornerShape(50))
            .background(if (onDark) Color(0x33FFFFFF) else Color(0x99000000))
            .clickable(onClick = onClick).padding(horizontal = if (big) 10.dp else 7.dp, vertical = if (big) 5.dp else 3.dp)
            .testTag("today_photo_species"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            TodayViewModel.speciesEmoji(tag.group) + " " + speciesName(tag.name, tag.latin),
            color = Color.White,
            fontSize = if (big) 13.sp else 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
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
    Box(modifier) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(VideoThumb.model(photo.uri, photo.isVideo)).size(if (big) 900 else 360).crossfade(true).build(),
            contentDescription = photo.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick).testTag("today_thumb"),
        )
        if (photo.isVideo) VideoBadge(photo.durationMs.takeIf { it > 0 }, big, Modifier.align(Alignment.BottomEnd))
    }
}

/** ▶ 0:23 in the corner of a video's still. */
@Composable
private fun VideoBadge(durationMs: Long?, big: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(if (big) 10.dp else 6.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000))
            .padding(start = 4.dp, end = if (durationMs != null) 7.dp else 4.dp, top = 2.dp, bottom = 2.dp)
            .testTag("today_video_badge"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(if (big) 16.dp else 13.dp))
        if (durationMs != null) Text(VideoThumb.duration(durationMs), color = Color.White, fontSize = if (big) 12.sp else 10.sp)
    }
}

/**
 * Thumbnails waiting to go with a note — in the composer, or while editing
 * one — each with an ×, and [onAdd] (if any) as a last "+" tile.
 */
@Composable
private fun PendingStrip(items: List<PendingPhoto>, onRemove: (PendingPhoto) -> Unit, onAdd: (() -> Unit)? = null, importing: Int = 0, modifier: Modifier = Modifier) {
    LazyRow(modifier.testTag("today_pending"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.relativePath }) { p ->
            Box(Modifier.size(64.dp)) {
                if (p.uri.isEmpty()) {
                    // Not on this phone: kept, so saving an edit doesn't drop the reference.
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                } else {
                    AsyncImage(
                        model = VideoThumb.model(p.uri, p.isVideo),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
                if (p.isVideo) VideoBadge(null, big = false, modifier = Modifier.align(Alignment.BottomStart))
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(20.dp).clip(CircleShape)
                        .background(Color(0xCC222222)).clickable { onRemove(p) }.testTag("today_pending_remove"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.today_remove_photo), tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }
        }
        if (importing > 0) item(key = "importing") {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (onAdd != null) item(key = "add") {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .clickable(onClick = onAdd).testTag("today_edit_add_photos"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = stringResource(R.string.today_add_photos), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
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
    importing: Int,
    anchor: NoteAnchor?,
    zone: ZoneId,
    focus: FocusRequester,
    onDraft: (String) -> Unit,
    onCamera: () -> Unit,
    onPick: () -> Unit,
    onDiscard: (PendingPhoto) -> Unit,
    onClearAnchor: () -> Unit,
    onSend: () -> Unit,
) {
    // Picked photos go even without words; "write about this" needs the words — the photos are already there.
    val canSend = importing == 0 && (draft.isNotBlank() || (pending.isNotEmpty() && anchor == null))
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
                if (anchor != null) {
                    // Which moment this note will be written at: the group's, not now.
                    val at = remember(anchor.atMs) {
                        val t = Instant.ofEpochMilli(anchor.atMs).atZone(zone)
                        val today = LocalDate.now(zone)
                        (if (t.toLocalDate() == today) "" else dayLabel(t.toLocalDate()) + " ") + DateTimeFormatter.ofPattern("HH:mm").format(t)
                    }
                    Row(
                        Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp).testTag("today_anchor"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.today_writing_about, at),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        IconButton(onClick = onClearAnchor, modifier = Modifier.size(24.dp).testTag("today_anchor_clear")) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.today_cancel), modifier = Modifier.size(14.dp))
                        }
                    }
                }
                if (pending.isNotEmpty() || importing > 0) {
                    PendingStrip(
                        pending, onRemove = onDiscard, importing = importing,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp),
                    )
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
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(focus).testTag("today_input"),
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
                    Spacer(Modifier.width(8.dp))
                    InputCircleButton(onClick = onPick) {
                        Icon(
                            Icons.Outlined.Image,
                            contentDescription = stringResource(R.string.today_pick_photos),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp).testTag("today_pick"),
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
internal fun PhotoViewer(
    photos: List<CameraPhoto>,
    start: Int,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onIdentify: ((CameraPhoto) -> Unit)? = null,
    tags: Map<String, com.openminis.app.data.species.PhotoTag> = emptyMap(),
    onTag: (String) -> Unit = {},
) {
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
                    if (photos[page].isVideo) {
                        VideoPage(photos[page], active = pager.currentPage == page)
                    } else {
                        AsyncImage(
                            model = Uri.parse(photos[page].uri),
                            contentDescription = photos[page].displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                val p = photos[pager.currentPage]
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    // Species and "Identify" get their own row, so the file name never squeezes them (§17.3).
                    val tag = tags[p.relativePath]
                    val identify = onIdentify?.takeIf { !p.isVideo }
                    if (tag != null || identify != null) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (tag != null) {
                                SpeciesTag(tag, big = true, onClick = { onTag(tag.latin) }, modifier = Modifier.widthIn(max = 240.dp), onDark = true)
                            }
                            Spacer(Modifier.weight(1f))
                            if (identify != null) {
                                Row(
                                    Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).clickable { identify(p) }
                                        .padding(horizontal = 14.dp, vertical = 8.dp).testTag("today_viewer_identify"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Outlined.Eco, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.today_menu_identify), color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    Text(
                        clockTime(p.takenAtMs, zone) + "  ·  " + p.displayName,
                        color = Color(0xFFBBBBBB),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * A video in the viewer: its still and a ▶ until tapped, then it plays in
 * place. Tap to pause or resume; the bar below seeks. Swiping to another page
 * stops it.
 */
@Composable
private fun VideoPage(video: CameraPhoto, active: Boolean) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) started = false }
    Box(Modifier.fillMaxSize().testTag("today_viewer_video"), contentAlignment = Alignment.Center) {
        if (!started) {
            AsyncImage(
                model = VideoThumb.model(video.uri, true),
                contentDescription = video.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Color(0x66000000)).clickable { started = true }.testTag("today_video_play"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.today_play_video), tint = Color.White, modifier = Modifier.size(40.dp))
            }
            return@Box
        }
        var player by remember { mutableStateOf<VideoView?>(null) }
        var playing by remember { mutableStateOf(true) }
        var position by remember { mutableIntStateOf(0) }
        var duration by remember { mutableIntStateOf(video.durationMs.toInt()) }
        var seeking by remember { mutableStateOf<Float?>(null) }
        // Fit the screen at the video's own shape (from the player once prepared: it knows the rotation).
        var ratio by remember { mutableFloatStateOf(if (video.width > 0 && video.height > 0) video.width.toFloat() / video.height else 16f / 9f) }
        // The bar follows the video only while it plays.
        LaunchedEffect(player, playing) {
            val v = player ?: return@LaunchedEffect
            while (playing) {
                runCatching { if (seeking == null) position = v.currentPosition }
                delay(200)
            }
        }
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(Uri.parse(video.uri))
                    setOnPreparedListener { mp ->
                        if (mp.videoWidth > 0 && mp.videoHeight > 0) ratio = mp.videoWidth.toFloat() / mp.videoHeight
                        duration = mp.duration.coerceAtLeast(0); start(); playing = true
                    }
                    setOnCompletionListener { playing = false; position = duration }
                    setOnErrorListener { _, _, _ -> playing = false; true }
                    player = this
                }
            },
            onRelease = { it.stopPlayback(); player = null },
            // Largest box of that shape that fits: full width, or full height for a tall video.
            modifier = Modifier.aspectRatio(ratio),
        )
        // Tap anywhere: pause / resume (from the start again once it has ended).
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                val v = player ?: return@clickable
                runCatching {
                    if (v.isPlaying) { v.pause(); playing = false }
                    else { if (duration in 1..position + 300) v.seekTo(0); v.start(); playing = true }
                }
            },
        )
        if (!playing) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(56.dp))
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(VideoThumb.duration((seeking?.toLong() ?: position.toLong())), color = Color.White, fontSize = 11.sp)
            Slider(
                value = seeking ?: position.toFloat(),
                onValueChange = { seeking = it },
                onValueChangeFinished = { seeking?.let { t -> runCatching { player?.seekTo(t.toInt()) }; position = t.toInt() }; seeking = null },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(VideoThumb.duration(duration.toLong()), color = Color.White, fontSize = 11.sp)
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
