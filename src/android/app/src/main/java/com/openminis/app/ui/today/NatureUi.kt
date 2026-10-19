package com.openminis.app.ui.today

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.Path
import com.openminis.app.ui.components.MinisMochi
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.MutableTransitionState
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.openminis.app.R
import com.openminis.app.data.photos.CameraPhoto
import com.openminis.app.data.species.SpeciesId
import com.openminis.app.data.species.SpeciesInfo
import com.openminis.app.data.species.isLatinOnly
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlinx.coroutines.delay

// [T-today-species] Everything the user sees of species ID (SPEC §17):
// the suggestion under a photo group, the "Identify" sheet, the little
// celebration after recording, and the nature collection pages.

/** Soft greens for the nature pages, readable on light and dark backgrounds alike. */
internal val Leaf = Color(0xFF3F7D4E)
internal val LeafSoft = Color(0x1F3F7D4E)

@Composable
fun groupLabel(group: String?): String = stringResource(
    when (group) {
        "Plantae" -> R.string.today_group_plants
        "Aves" -> R.string.today_group_birds
        "Insecta" -> R.string.today_group_insects
        "Mammalia" -> R.string.today_group_mammals
        "Fungi" -> R.string.today_group_fungi
        "Reptilia" -> R.string.today_group_reptiles
        "Amphibia" -> R.string.today_group_amphibians
        "Actinopterygii" -> R.string.today_group_fish
        "Arachnida" -> R.string.today_group_arachnids
        "Mollusca" -> R.string.today_group_molluscs
        else -> R.string.today_group_other
    },
)

/** A recorded name for display: a genus (one-word latin) reads "a kind of X", in the language of the moment. */
@Composable
internal fun speciesName(name: String, latin: String): String = if (' ' in latin.trim()) name else genusPhrase(name)

/** "秋海棠属的一种", or "Begonia 属的一种" when only the latin genus is known. */
@Composable
private fun genusPhrase(name: String): String =
    if (name.isLatinOnly()) stringResource(R.string.today_species_genus_latin, name) else stringResource(R.string.today_species_genus, name)

/** "Very likely X" / "Looks like X" / "a kind of X": how sure, in words rather than percentages. */
@Composable
private fun guessPhrase(name: String, genus: Boolean, prob: Float): String {
    val shown = if (genus) genusPhrase(name) else name
    return if (prob >= 0.8f && !genus) stringResource(R.string.today_species_conf_high, shown)
    else stringResource(R.string.today_species_looks_like, shown)
}

@Composable
internal fun PhotoImage(photo: CameraPhoto?, modifier: Modifier, size: Int = 600, fallback: String = "✦") {
    if (photo == null) {
        Box(modifier.background(LeafSoft), contentAlignment = Alignment.Center) { Text(fallback, fontSize = 34.sp) }
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(Uri.parse(photo.uri)).size(size).crossfade(true).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

// ───────────────────────── Under a photo group (§17.2) ─────────────────────────

/**
 * "Looks like a light-vented bulbul" with the photo it is about, so in a
 * group of nine it is clear which one. Tapping it opens "What is it?";
 * × says no. It never adds anything by itself.
 */
@Composable
internal fun SpeciesSuggestionChip(
    photo: CameraPhoto,
    count: Int,
    s: SpeciesSuggestion,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    // Fades in only when just identified (not on every scroll back to it).
    fresh: Boolean = false,
    onAppeared: () -> Unit = {},
) {
    AppearOnce(fresh, onAppeared) {
        Row(
            Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(LeafSoft).clickable(onClick = onOpen).testTag("today_species_chip"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhotoImage(photo, Modifier.padding(6.dp).size(40.dp).clip(RoundedCornerShape(10.dp)), size = 160)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    TodayViewModel.speciesEmoji(s.group) + " " + guessPhrase(s.name, s.genus, s.prob),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    s.latin + if (count > 1) "  ·  " + pluralStringResource(R.plurals.today_species_photos, count, count) else "",
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // Only a look, never an add: adding happens on the "What is it?" page, by a deliberate button.
            Text(stringResource(R.string.today_species_look), color = Leaf, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 2.dp))
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.today_species_not),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

// ───────────────────────── Identify (§17.3) ─────────────────────────

/**
 * The photo large, a light sweeping over it while the model looks; then the
 * candidates as cards with a confidence bar. Several photos → a strip to pick
 * which one, looked at in the background so flipping is instant, a dot on
 * those that look alive. None right → ask Minis with the photo. [rejected]
 * is a kind the user just said no to: not offered again here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IdentifySheet(
    vm: TodayViewModel,
    photos: List<CameraPhoto>,
    start: CameraPhoto?,
    tags: Map<String, com.openminis.app.data.species.PhotoTag>,
    rejected: String? = null,
    onUndoReject: (() -> Unit)? = null,
    onPick: (CameraPhoto, SpeciesSuggestion) -> Unit,
    onUntag: (CameraPhoto) -> Unit,
    onAsk: (CameraPhoto) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf(start ?: vm.startPhoto(photos)) }
    val results = remember { mutableStateMapOf<String, SpeciesId.Result?>() }
    // A box the user drew on the current photo; its result replaces the automatic one.
    var drawn by remember { mutableStateOf<SpeciesId.Region?>(null) }
    // A candidate opened on its "What is it?" page; adding happens there.
    var opened by remember { mutableStateOf<SpeciesSuggestion?>(null) }
    val openedFor = current
    if (opened != null && openedFor != null) {
        WhatIsItPage(
            vm = vm,
            photos = listOf(openedFor),
            s = opened!!,
            recorded = tags[openedFor.relativePath],
            onAdd = { onPick(openedFor, opened!!); opened = null },
            onRemove = { onUntag(openedFor); opened = null },
            // Back to the other candidates.
            onNot = { opened = null },
            onOthers = null,
            onDismiss = { opened = null },
        )
    }
    // The rest of the strip, one at a time once the photo on screen has its answer.
    LaunchedEffect(Unit) {
        snapshotFlow { current?.let { results.containsKey(it.relativePath) } ?: true }.first { it }
        // [T-today-species-heat] What is already known shows for the whole strip; only a few
        // others are looked at ahead, and none while the phone is warm.
        for (ph in photos) if (!results.containsKey(ph.relativePath)) vm.keptResult(ph)?.let { results[ph.relativePath] = it }
        for (ph in photos.filter { !results.containsKey(it.relativePath) }.take(PREFETCH)) {
            if (vm.speciesWarm()) break
            if (!results.containsKey(ph.relativePath)) results[ph.relativePath] = vm.identify(ph)
        }
    }
    LaunchedEffect(current) {
        drawn = null
        val p = current ?: return@LaunchedEffect
        if (!results.containsKey(p.relativePath)) results[p.relativePath] = vm.identify(p)
    }
    LaunchedEffect(drawn) {
        val p = current ?: return@LaunchedEffect
        val r = drawn ?: return@LaunchedEffect
        results.remove(p.relativePath)
        results[p.relativePath] = vm.identify(p, r)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("today_identify_sheet")) {
            if (onUndoReject != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.today_species_dismissed), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = onUndoReject, modifier = Modifier.testTag("today_identify_undo")) { Text(stringResource(R.string.today_undo)) }
                }
            }
            val p = current
            RegionPhoto(
                photo = p,
                looking = p == null || !results.containsKey(p.relativePath),
                region = p?.let { results[it.relativePath] }?.region,
                onDraw = { drawn = it },
            )
            Text(
                stringResource(if (drawn != null) R.string.today_identify_drawn else R.string.today_identify_draw_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp).align(Alignment.CenterHorizontally),
            )
            if (photos.size > 1) {
                LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(photos, key = { it.id }) { ph ->
                        val sel = ph.id == p?.id
                        Box {
                            PhotoImage(
                                ph,
                                Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                                    .border(if (sel) 2.dp else 0.dp, if (sel) Leaf else Color.Transparent, RoundedCornerShape(10.dp))
                                    .graphicsLayer { alpha = if (sel) 1f else 0.6f }
                                    .clickable { current = ph },
                                size = 200,
                            )
                            if (results[ph.relativePath]?.looksLikeOrganism == true) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(10.dp).clip(CircleShape)
                                        .background(Leaf).border(1.5.dp, Color.White, CircleShape),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val r = p?.let { results[it.relativePath] }
            when {
                p == null || !results.containsKey(p.relativePath) -> Text(
                    stringResource(R.string.today_identify_working),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                r == null || r.top.none { it.latin != rejected } -> Text(stringResource(R.string.today_identify_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text(
                        stringResource(if (r.looksLikeOrganism) R.string.today_identify_title else R.string.today_identify_not_life),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    r.top.filter { it.latin != rejected }.forEachIndexed { i, g ->
                        CandidateCard(vm, g, first = i == 0 && r.looksLikeOrganism, onClick = { opened = SpeciesSuggestion.of(g, r.region) })
                    }
                }
            }
            // Already recorded: say so, and let a wrong one be taken off.
            p?.let { tags[it.relativePath] }?.let { t ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.today_identify_recorded, TodayViewModel.speciesEmoji(t.group) + " " + speciesName(t.name, t.latin)),
                        fontSize = 13.sp,
                        color = Leaf,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onUntag(p) }, modifier = Modifier.testTag("today_identify_untag")) {
                        Text(stringResource(R.string.today_identify_untag), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (p != null && results.containsKey(p.relativePath)) {
                TextButton(onClick = { onAsk(p) }, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("today_identify_ask")) {
                    Icon(Icons.Outlined.Forum, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.today_identify_ask))
                }
                Text(
                    stringResource(R.string.today_identify_hint),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

/** Photos of a group looked at ahead in the identify sheet (besides kept results); the NPU takes about a second each. */
private const val PREFETCH = 3

/**
 * The whole photo (not cropped, so a box means the same on screen and to the
 * model), with a green frame on the part the answer came from. Dragging
 * draws a new box: on release, only that part is identified (§17.3).
 */
@Composable
private fun RegionPhoto(photo: CameraPhoto?, looking: Boolean, region: SpeciesId.Region?, onDraw: (SpeciesId.Region) -> Unit) {
    var aspect by remember(photo?.id) { mutableStateOf(4f / 3f) }
    var dragFrom by remember { mutableStateOf<Offset?>(null) }
    var dragTo by remember { mutableStateOf<Offset?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val maxH = 340.dp
        val w = if (maxWidth / aspect > maxH) maxH * aspect else maxWidth
        val h = w / aspect
        Box(
            Modifier.size(w, h).clip(RoundedCornerShape(14.dp)).background(Color.Black)
                .pointerInput(photo?.id) {
                    detectDragGestures(
                        onDragStart = { dragFrom = it; dragTo = it },
                        onDrag = { change, amount -> change.consume(); dragTo = dragTo?.plus(amount) },
                        onDragEnd = {
                            val a = dragFrom
                            val b = dragTo
                            dragFrom = null
                            dragTo = null
                            if (a != null && b != null) {
                                val l = (minOf(a.x, b.x) / size.width).coerceIn(0f, 1f)
                                val t = (minOf(a.y, b.y) / size.height).coerceIn(0f, 1f)
                                val r = (maxOf(a.x, b.x) / size.width).coerceIn(0f, 1f)
                                val btm = (maxOf(a.y, b.y) / size.height).coerceIn(0f, 1f)
                                // Ignore taps and slips: a box must cover a little of the photo.
                                if (r - l > 0.06f && btm - t > 0.06f) onDraw(SpeciesId.Region(l, t, r, btm))
                            }
                        },
                        onDragCancel = { dragFrom = null; dragTo = null },
                    )
                }
                .testTag("today_identify_photo"),
        ) {
            if (photo != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(Uri.parse(photo.uri)).size(1200).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onSuccess = { s ->
                        val d = s.result.drawable
                        if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) aspect = d.intrinsicWidth.toFloat() / d.intrinsicHeight
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (looking) ScanSweep()
            Canvas(Modifier.fillMaxSize()) {
                val a = dragFrom
                val b = dragTo
                if (a != null && b != null) {
                    val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                    val sz = Size(abs(a.x - b.x), abs(a.y - b.y))
                    drawRect(Color(0x33FFFFFF), tl, sz)
                    drawRect(Color.White, tl, sz, style = Stroke(2.dp.toPx()))
                } else if (region != null && !looking) {
                    val tl = Offset(region.left * size.width, region.top * size.height)
                    val sz = Size((region.right - region.left) * size.width, (region.bottom - region.top) * size.height)
                    drawRoundRect(Color(0xFF7BD88F), tl, sz, CornerRadius(8.dp.toPx()), style = Stroke(2.5.dp.toPx()))
                }
            }
        }
    }
}

/** A band of light crossing the photo: the model is looking. */
@Composable
private fun ScanSweep() {
    val t = rememberInfiniteTransition(label = "scan")
    val x by t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "x")
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0x22000000))) {
        val w = maxWidth
        Box(
            Modifier.fillMaxSize().offset(x = w * x - w * 0.3f).width(w * 0.3f)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x66FFFFFF), Color.Transparent))),
        )
    }
}

@Composable
private fun CandidateCard(vm: TodayViewModel, g: SpeciesId.Guess, first: Boolean, onClick: () -> Unit) {
    val bar by animateFloatAsState(g.prob.coerceIn(0.02f, 1f), tween(700), label = "bar")
    // What it looks like, to hold against the user's own photo (§17.3).
    val refs by produceState(emptyList<SpeciesInfo.RefPhoto>(), g.latin) { value = vm.refPhotos(g.latin, if (g.genus) null else g.taxon.inatId) }
    var viewing by remember { mutableStateOf(false) }
    if (viewing && refs.isNotEmpty()) RefPhotosDialog(refs, if (g.genus) genusPhrase(g.name) else g.name, g.latin, onDismiss = { viewing = false })
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (first) LeafSoft else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (first) BorderStroke(1.dp, Leaf.copy(alpha = 0.4f)) else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).testTag("today_identify_option"),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = refs.isNotEmpty()) { viewing = true }.testTag("today_identify_ref"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(TodayViewModel.speciesEmoji(g.taxon.group), fontSize = 20.sp)
                    refs.firstOrNull()?.let { ref ->
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(ref.url).crossfade(true).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (g.genus) genusPhrase(g.name) else g.name,
                        fontSize = 16.sp,
                        fontWeight = if (first) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(g.latin, fontSize = 12.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    stringResource(
                        when {
                            g.prob >= 0.8f -> R.string.today_identify_conf_high
                            g.prob >= 0.4f -> R.string.today_identify_conf_mid
                            else -> R.string.today_identify_conf_low
                        },
                    ),
                    fontSize = 12.sp,
                    color = if (first) Leaf else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                Box(Modifier.fillMaxWidth(bar).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (first) Leaf else MaterialTheme.colorScheme.outline))
            }
        }
    }
}

/** A candidate's reference photos, large, swipeable, each with its credit line. */
@Composable
private fun RefPhotosDialog(refs: List<SpeciesInfo.RefPhoto>, name: String, latin: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(20.dp).fillMaxWidth()) {
            Column(Modifier.padding(bottom = 14.dp)) {
                val pager = rememberPagerState { refs.size }
                HorizontalPager(pager, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))) { i ->
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(refs[i].largeUrl).crossfade(true).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
                    Text(name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(latin, fontSize = 12.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.today_identify_ref_credit, refs[pager.currentPage].attribution) +
                            if (refs.size > 1) "  ·  ${pager.currentPage + 1}/${refs.size}" else "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ───────────────────────── What is it? (§17.3) ─────────────────────────

/**
 * One candidate, for someone who wants to know what they photographed
 * before adding anything: their own part of the photo beside reference
 * photos, the name, how sure, a short introduction — and only then one
 * clear button to add it. [photos] are the ones it was suggested for (a
 * group's photos of the same kind are added together). [onNot] is "not
 * it"; [onOthers], when given, leads to the other candidates.
 */
@Composable
internal fun WhatIsItPage(
    vm: TodayViewModel,
    photos: List<CameraPhoto>,
    s: SpeciesSuggestion,
    recorded: com.openminis.app.data.species.PhotoTag?,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onNot: () -> Unit,
    onOthers: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val photo = photos.first()
    val taxon by produceState<SpeciesId.Taxon?>(null, s.latin) { value = vm.describe(s) }
    val profile by produceState<SpeciesId.Profile?>(null, s.latin) { if (!s.genus) value = vm.speciesProfile(s.latin) }
    val refs by produceState(emptyList<SpeciesInfo.RefPhoto>(), s.latin, taxon) {
        taxon?.let { value = vm.refPhotos(s.latin, if (s.genus) null else it.inatId) }
    }
    val info by produceState<SpeciesInfo.Info?>(null, taxon) { taxon?.let { value = vm.speciesInfo(it) } }
    val name = if (s.genus) genusPhrase(s.name) else s.name
    val already = recorded?.latin == s.latin
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag("today_whatis")) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                    Text(stringResource(R.string.today_whatis_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                    // Side by side: what you took, and what it looks like.
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            RegionCrop(photo, s.region, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)))
                            Text(stringResource(R.string.today_whatis_yours), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            val pager = rememberPagerState { refs.size.coerceAtLeast(1) }
                            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                if (refs.isEmpty()) {
                                    Text(TodayViewModel.speciesEmoji(s.group), fontSize = 34.sp)
                                } else {
                                    HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current).data(refs[i].largeUrl).crossfade(true).build(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                            Text(
                                if (refs.isEmpty()) stringResource(R.string.today_whatis_ref) else stringResource(R.string.today_whatis_ref) + if (refs.size > 1) "  ${pager.currentPage + 1}/${refs.size}" else "",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(TodayViewModel.speciesEmoji(s.group) + " " + name, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(s.latin, fontSize = 14.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    // How sure, in words and a bar — never a bare percentage.
                    val sure by animateFloatAsState(s.prob.coerceIn(0.02f, 1f), tween(700), label = "sure")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(
                                when {
                                    s.prob >= 0.8f -> R.string.today_identify_conf_high
                                    s.prob >= 0.4f -> R.string.today_identify_conf_mid
                                    else -> R.string.today_identify_conf_low
                                },
                            ),
                            fontSize = 13.sp,
                            color = Leaf,
                            modifier = Modifier.width(72.dp),
                        )
                        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
                            Box(Modifier.fillMaxWidth(sure).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Leaf))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tag(TodayViewModel.speciesEmoji(s.group) + " " + groupLabel(s.group))
                        (taxon?.genusZh ?: taxon?.genusLatin)?.takeIf { !s.genus }?.let { Tag(it) }
                        profile?.taxon?.count?.takeIf { it > 0 }?.let { Tag(commonness(it)) }
                    }
                    Section(stringResource(R.string.today_nature_know))
                    AboutCard(info)
                    refs.firstOrNull()?.let {
                        Text(stringResource(R.string.today_identify_ref_credit, it.attribution), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                    }
                    if (onOthers != null) {
                        TextButton(onClick = onOthers, modifier = Modifier.padding(top = 6.dp).testTag("today_whatis_others")) {
                            Text(stringResource(R.string.today_whatis_others))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                // The one deliberate step: a big button, apart from everything else on the page.
                Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (already) {
                            Text("✓ " + stringResource(R.string.today_whatis_in), color = Leaf, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = onRemove, modifier = Modifier.testTag("today_whatis_remove")) {
                                Text(stringResource(R.string.today_whatis_remove), color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            androidx.compose.material3.OutlinedButton(onClick = onNot, modifier = Modifier.height(52.dp).testTag("today_whatis_not")) {
                                Text(stringResource(R.string.today_whatis_not))
                            }
                            Spacer(Modifier.width(12.dp))
                            androidx.compose.material3.Button(
                                onClick = onAdd,
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Leaf, contentColor = Color.White),
                                modifier = Modifier.weight(1f).height(52.dp).testTag("today_whatis_add"),
                            ) {
                                Text(
                                    stringResource(R.string.today_whatis_add) + if (photos.size > 1) " · " + pluralStringResource(R.plurals.today_species_photos, photos.size, photos.size) else "",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Just [region] of the photo (or its middle), filling a square. */
@Composable
private fun RegionCrop(photo: CameraPhoto, region: SpeciesId.Region?, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        val request = ImageRequest.Builder(LocalContext.current).data(Uri.parse(photo.uri)).size(1200).crossfade(true).build()
        if (region == null) {
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val rw = (region.right - region.left).coerceAtLeast(0.01f)
            val rh = (region.bottom - region.top).coerceAtLeast(0.01f)
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                // The whole photo, scaled so the region fills the box and shifted to it.
                modifier = Modifier.layout { m, c ->
                    val w = (c.maxWidth / rw).roundToInt()
                    val h = (c.maxHeight / rh).roundToInt()
                    val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                    layout(c.maxWidth, c.maxHeight) { p.place(-(region.left * w).roundToInt(), -(region.top * h).roundToInt()) }
                },
            )
        }
    }
}

// ───────────────────────── After recording (§17.2) ─────────────────────────

/**
 * Every time something goes into the collection (§17.2), a card is dealt:
 * the screen dims; the card rises face down and flips over to the photo;
 * on landing "No.12" (or "×3" when seen again) is slammed on with a thud,
 * the card shakes, a shockwave and a burst of leaves and petals go out, and
 * a shine runs across the photo. Then the name rises letter by letter and
 * the card floats among twinkling stars. A new kind says "New species"; the
 * first one ever, the first of a group (first bird) and round numbers
 * (10, 50, 100…) get a line of their own. Tap anywhere to go on; "Take a
 * look" opens the species. The Minis mochi pops up by the card and cheers
 * (the companion's "done", docs/design/companion).
 */
@Composable
internal fun NewSpeciesReveal(event: TodayViewModel.Event.Collected?, onOpen: (String) -> Unit, onGone: () -> Unit) {
    var last by remember { mutableStateOf(event) }
    if (event != null) last = event
    // Its own full-screen window, over the tab bar and the system bars; kept until the fade-out ends.
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = event != null
    if (!shown.currentState && !shown.targetState) return
    Dialog(onDismissRequest = onGone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val dialogView = LocalView.current
    LaunchedEffect(window) {
        window?.setDimAmount(0f)
        window?.let { WindowCompat.getInsetsController(it, dialogView).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false } }
    }
    AnimatedVisibility(
        shown,
        enter = fadeIn(tween(280)),
        exit = fadeOut(tween(240)) + scaleOut(tween(240), targetScale = 0.9f),
    ) {
        val e = last ?: return@AnimatedVisibility
        val view = LocalView.current
        val rise = remember(e) { Animatable(0f) }    // from below to the middle
        val flip = remember(e) { Animatable(0f) }    // face down → face up
        val stamp = remember(e) { Animatable(0f) }   // the number slammed on
        val shake = remember(e) { Animatable(0f) }   // 1 at impact, dying away
        val wave = remember(e) { Animatable(0f) }    // shockwave and burst, 0 → 1
        val shine = remember(e) { Animatable(0f) }   // light across the photo
        val words = remember(e) { Animatable(0f) }   // title, name and the rest
        val pill = remember(e) { Animatable(0f) }
        val mochi = remember(e) { Animatable(0f) }   // Minis pops up by the card to cheer
        var cheer by remember(e) { mutableIntStateOf(0) }
        LaunchedEffect(e) {
            delay(180) // the sheet it came from slides away first
            launch { rise.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 260f)) }
            delay(260)
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            flip.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
            // Impact.
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
            launch { stamp.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 700f)) }
            launch { delay(90); cheer++; mochi.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 420f)) }
            launch { shake.snapTo(1f); shake.animateTo(0f, tween(520, easing = LinearEasing)) }
            launch { wave.animateTo(1f, tween(1900, easing = LinearEasing)) }
            launch { delay(120); shine.animateTo(1f, tween(750, easing = FastOutSlowInEasing)) }
            launch { delay(140); words.animateTo(1f, tween(700, easing = LinearEasing)) }
            delay(620)
            if (e.isNew && special(e)) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                pill.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f))
            }
        }
        val idle = rememberInfiniteTransition(label = "idle")
        val spin by idle.animateFloat(0f, 360f, infiniteRepeatable(tween(30000, easing = LinearEasing)), label = "spin")
        val breathe by idle.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
        val twinkle by idle.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "twinkle")
        val special = when {
            !e.isNew -> null
            e.number == 1 -> stringResource(R.string.today_reveal_first)
            e.number in MILESTONES -> stringResource(R.string.today_reveal_milestone, e.number)
            e.firstOfGroup -> stringResource(R.string.today_reveal_first_group, groupLabel(e.group))
            else -> null
        }
        val title = "✦ " + (if (e.isNew) stringResource(R.string.today_reveal_title) else stringResource(R.string.today_collected_again, e.times)) + " ✦"
        val landed = stamp.value.coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxSize()
                .background(Brush.radialGradient(listOf(Color(0xF21A2E21), Color(0xFF070C09))))
                .clickable(interactionSource = null, indication = null, onClick = onGone)
                .testTag("today_new_species"),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 28.dp)) {
                // The title comes in a letter at a time.
                Row {
                    title.forEachIndexed { i, ch ->
                        val t = ((words.value * 1.8f - i * 0.06f)).coerceIn(0f, 1f)
                        Text(
                            ch.toString(),
                            color = LeafGlow,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 3.sp,
                            modifier = Modifier.graphicsLayer { alpha = t; translationY = (1f - t) * 10.dp.toPx() },
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Box(contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(440.dp)) {
                        val glow = landed * (0.75f + 0.25f * breathe)
                        // Soft glow and slow rays behind the card.
                        drawCircle(Brush.radialGradient(listOf(LeafGlow.copy(alpha = 0.35f * glow), Color.Transparent), center, size.minDimension * 0.46f))
                        rotate(spin) {
                            for (i in 0 until 14) rotate(i * 360f / 14) {
                                drawArc(
                                    Brush.radialGradient(listOf(LeafGlow.copy(alpha = 0.18f * glow), Color.Transparent), center, size.minDimension / 2),
                                    startAngle = -5f, sweepAngle = 10f, useCenter = true,
                                )
                            }
                        }
                        // The shockwave on impact.
                        val w = wave.value
                        if (w in 0.001f..0.5f) {
                            val k = w / 0.5f
                            drawCircle(
                                LeafGlow.copy(alpha = 0.7f * (1f - k)),
                                radius = 120.dp.toPx() + k * 110.dp.toPx(),
                                style = Stroke(width = (10f * (1f - k) + 1f).dp.toPx()),
                            )
                        }
                        drawSparkles(landed, twinkle, e.latin.hashCode())
                        drawConfetti(w, e.latin.hashCode())
                    }
                    val shakeAngle = sin(shake.value * 26f) * shake.value * 5f
                    Box(
                        Modifier.width(236.dp).aspectRatio(0.8f)
                            .graphicsLayer {
                                val r = rise.value
                                translationY = (1f - r) * 560.dp.toPx() + landed * sin(breathe * PI.toFloat()) * -6.dp.toPx()
                                rotationY = 180f * (1f - flip.value)
                                rotationZ = -10f * (1f - r) - 3f * landed + shakeAngle
                                val sc = 0.7f + 0.3f * r + 0.06f * shake.value
                                scaleX = sc
                                scaleY = sc
                                cameraDistance = 16f * density
                            },
                    ) {
                        if (flip.value < 0.5f) CardBack(e.group)
                        else CardFace(e, stamp.value, shine.value)
                    }
                    // Minis, peeking out at the card's lower left, jumps and cheers.
                    MinisMochi(
                        cheer = cheer,
                        modifier = Modifier.size(112.dp).offset(x = (-112).dp, y = 118.dp)
                            .graphicsLayer {
                                val m = mochi.value
                                alpha = m.coerceIn(0f, 1f)
                                scaleX = m
                                scaleY = m
                                translationY = (1f - m.coerceIn(0f, 1f)) * 40.dp.toPx()
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                            },
                    )
                }
                Spacer(Modifier.height(20.dp))
                val nameIn = ((words.value - 0.15f) / 0.5f).coerceIn(0f, 1f)
                Text(
                    speciesName(e.name, e.latin),
                    color = Color.White,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        alpha = nameIn
                        translationY = (1f - nameIn) * 28.dp.toPx()
                        scaleX = 0.85f + 0.15f * nameIn
                        scaleY = 0.85f + 0.15f * nameIn
                    },
                )
                val latinIn = ((words.value - 0.35f) / 0.4f).coerceIn(0f, 1f)
                Text(
                    e.latin,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 14.sp,
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer { alpha = latinIn; translationY = (1f - latinIn) * 12.dp.toPx() },
                )
                if (special != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "🎉 $special",
                        color = Color(0xFF1B2E20),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .graphicsLayer { alpha = pill.value.coerceIn(0f, 1f); scaleX = pill.value; scaleY = pill.value }
                            .clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(LeafGlow, Color(0xFFFFE08A))))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                val buttonIn = ((words.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { alpha = buttonIn }) {
                    Button(
                        onClick = { onOpen(e.latin) },
                        enabled = buttonIn > 0f,
                        colors = ButtonDefaults.buttonColors(containerColor = Leaf),
                        modifier = Modifier.testTag("today_new_species_open"),
                    ) { Text(stringResource(R.string.today_reveal_open)) }
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.today_reveal_continue), color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
                }
            }
        }
    }
    }
}

/** Whether the reveal has a line of its own for this one. */
private fun special(e: TodayViewModel.Event.Collected) = e.number == 1 || e.number in MILESTONES || e.firstOfGroup

/** The card face down: green, with the kind's emoji and a question mark (turned so it reads while the card is past halfway). */
@Composable
private fun CardBack(group: String?) {
    Box(
        Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }.shadow(18.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF4E9A61), Color(0xFF2C5E39))))
            .padding(10.dp).border(1.5.dp, LeafGlow.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(TodayViewModel.speciesEmoji(group), fontSize = 56.sp)
            Text("?", color = LeafGlow, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The card face up: the photo in a white frame, the stamp, the emoji, and a passing shine. */
@Composable
private fun CardFace(e: TodayViewModel.Event.Collected, stamp: Float, shine: Float) {
    Box(
        Modifier.fillMaxSize()
            .shadow(22.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Color.White).padding(6.dp)
            .clip(RoundedCornerShape(17.dp)),
    ) {
        PhotoImage(e.photo, Modifier.fillMaxSize(), size = 700, fallback = TodayViewModel.speciesEmoji(e.group))
        if (shine in 0.001f..0.999f) {
            Canvas(Modifier.fillMaxSize()) {
                val x = -size.width + shine * size.width * 3f
                drawRect(
                    Brush.linearGradient(
                        0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.55f), 1f to Color.Transparent,
                        start = Offset(x - 80f, 0f), end = Offset(x + 80f, size.height * 0.4f),
                    ),
                )
            }
        }
        Text(
            if (e.isNew) "No.${e.number}" else "×${e.times}",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(12.dp)
                .graphicsLayer {
                    alpha = (stamp * 2f).coerceIn(0f, 1f)
                    val sc = 2.2f - 1.2f * stamp
                    scaleX = sc
                    scaleY = sc
                    rotationZ = -8f
                }
                .shadow(6.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp)).background(Leaf)
                .border(1.5.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Box(
            Modifier.align(Alignment.BottomEnd).padding(10.dp)
                .graphicsLayer { val t = stamp.coerceIn(0f, 1f); alpha = t; scaleX = t; scaleY = t }
                .size(42.dp).clip(CircleShape).background(Color(0xE6FFFFFF)),
            contentAlignment = Alignment.Center,
        ) { Text(TodayViewModel.speciesEmoji(e.group), fontSize = 22.sp) }
    }
}

/** Round numbers worth a line of their own on the reveal. */
private val MILESTONES = setOf(10, 25, 50, 100, 150, 200, 300, 500, 1000)

private val LeafGlow = Color(0xFFA8E6B4)
private val ConfettiColors = listOf(Color(0xFFA8E6B4), Color(0xFF6CC27E), Color(0xFFFFD86B), Color(0xFFFFFFFF), Color(0xFFFF9E80), Color(0xFF8FD3FF), Color(0xFFFFB3D1))

/**
 * Leaves, petals and streamers thrown out from behind the card, fluttering
 * as they fall and fade; each leaves a little after the one before. [t] runs 0 → 1.
 */
private fun DrawScope.drawConfetti(t: Float, seed: Int) {
    if (t <= 0f || t >= 1f) return
    val rnd = java.util.Random(seed.toLong())
    val reach = size.minDimension * 0.7f
    repeat(90) {
        val angle = rnd.nextFloat() * 2f * PI.toFloat()
        val speed = 0.35f + rnd.nextFloat() * 0.65f
        val color = ConfettiColors[rnd.nextInt(ConfettiColors.size)]
        val depth = 0.6f + rnd.nextFloat() * 0.8f
        val w = (6f + rnd.nextFloat() * 6f) * depth * density / 2.5f
        val spinRate = (rnd.nextFloat() - 0.5f) * 1080f
        val kind = rnd.nextInt(3)
        val start = rnd.nextFloat() * 0.18f
        val sway = rnd.nextFloat() * 2f * PI.toFloat()
        val p = ((t - start) / (1f - start)).coerceIn(0f, 1f)
        if (p <= 0f) return@repeat
        // Fast out, then slow; gravity and a side-to-side flutter take over.
        val out = 1f - (1f - p) * (1f - p) * (1f - p)
        val x = center.x + cos(angle) * reach * speed * out + sin(p * 9f + sway) * 14f * density * p
        val y = center.y + sin(angle) * reach * speed * out + p * p * reach * 0.5f
        val a = if (p < 0.65f) 1f else (1f - p) / 0.35f
        // Turning over: the width narrows and widens like a flake catching the light.
        val turn = 0.35f + 0.65f * kotlin.math.abs(cos(p * 12f + sway))
        rotate(spinRate * p + angle * 57f, Offset(x, y)) {
            when (kind) {
                0 -> drawPath(leafPath(x, y, w * 1.8f * turn, w), color.copy(alpha = a))
                1 -> drawOval(color.copy(alpha = a), Offset(x - w * turn, y - w * 0.5f), Size(w * 2f * turn, w))
                else -> drawRect(color.copy(alpha = a), Offset(x - w * 0.9f * turn, y - w * 0.25f), Size(w * 1.8f * turn, w * 0.5f))
            }
        }
    }
}

private fun leafPath(x: Float, y: Float, len: Float, wid: Float) = Path().apply {
    moveTo(x - len / 2, y)
    quadraticTo(x, y - wid, x + len / 2, y)
    quadraticTo(x, y + wid, x - len / 2, y)
    close()
}

/** Little four-point stars around the card, each twinkling in its own time. */
private fun DrawScope.drawSparkles(shown: Float, twinkle: Float, seed: Int) {
    if (shown <= 0f) return
    val rnd = java.util.Random(seed.toLong() * 31)
    repeat(12) {
        val angle = rnd.nextFloat() * 2f * PI.toFloat()
        val dist = size.minDimension * (0.33f + rnd.nextFloat() * 0.15f)
        val phase = rnd.nextFloat()
        val k = sin(((twinkle + phase) % 1f) * PI.toFloat())
        val r = (3f + 6f * k) * density * shown
        val c = Offset(center.x + cos(angle) * dist, center.y + sin(angle) * dist * 1.15f)
        val star = Path().apply {
            moveTo(c.x, c.y - r * 1.8f)
            quadraticTo(c.x, c.y, c.x + r * 1.8f, c.y)
            quadraticTo(c.x, c.y, c.x, c.y + r * 1.8f)
            quadraticTo(c.x, c.y, c.x - r * 1.8f, c.y)
            quadraticTo(c.x, c.y, c.x, c.y - r * 1.8f)
            close()
        }
        drawPath(star, Color.White.copy(alpha = (0.3f + 0.7f * k) * shown))
    }
}

// ───────────────────────── Nature collection (§17.4) ─────────────────────────

/**
 * Full-screen collection: how many kinds met, a filter by kind of life, and a
 * wall of photo cards numbered in the order they were first met. A card opens
 * the species page. [open] jumps straight to one species (from the banner or
 * a note's species line).
 *
 * Managing it: "Select" (or a long press on a card) picks kinds to remove;
 * the species page removes that kind, or just the photo on show. Always
 * asked first, and undoable from the snackbar; photos and notes are kept.
 */
@Composable
internal fun NatureCollectionPage(vm: TodayViewModel, open: String?, onAsk: (String, List<String>) -> Unit, onDismiss: () -> Unit) {
    // The last list at once, then brought up to date (only changed days are read again).
    var list by remember { mutableStateOf(vm.lastCollection) }
    var filter by remember { mutableStateOf<String?>(null) }
    // The kinds opened, one on top of another (look-alikes lead on); back steps out one at a time.
    var trail by remember { mutableStateOf(listOfNotNull(open)) }
    val detail = trail.lastOrNull()
    // Managing (§17.4): picked kinds, what is waiting for "Remove?", and the undo.
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var asking by remember { mutableStateOf<Removal?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.today_undo)
    LaunchedEffect(Unit) { list = vm.collection() }
    val ctx = LocalContext.current
    val remove: (Removal) -> Unit = { r ->
        scope.launch {
            val undo = vm.removeFromCollection(r.latins, r.photo?.let { setOf(it.relativePath) })
            list = vm.collection()
            selecting = false
            selected = emptySet()
            val text = if (r.photo != null) ctx.getString(R.string.today_nature_photo_removed) else ctx.getString(R.string.today_nature_deleted, r.latins.size)
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(text, actionLabel = undoLabel, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                undo()
                list = vm.collection()
            }
        }
    }
    BackHandler(enabled = selecting && detail == null) { selecting = false; selected = emptySet() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Status bar icons: dark on the light grid, light over a species photo.
        val view = LocalView.current
        val lightBg = MaterialTheme.colorScheme.background.luminance() > 0.5f
        LaunchedEffect(detail, lightBg) {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = lightBg && detail == null
            }
        }
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag("today_collection")) { Box(Modifier.fillMaxSize()) {
            val l = list
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) {
                        IconButton(onClick = { selecting = false; selected = emptySet() }) { Icon(Icons.Filled.Close, contentDescription = null) }
                        Text(stringResource(R.string.today_nature_selected, selected.size), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        // All of what the filter shows.
                        TextButton(onClick = { selected = l.orEmpty().filter { filter == null || (it.group ?: "") == filter }.map { it.latin }.toSet() }) {
                            Text(stringResource(R.string.today_nature_select_all))
                        }
                        val oneName = l.orEmpty().filter { it.latin in selected }.singleOrNull()?.let { speciesName(it.name, it.latin) }
                        IconButton(
                            onClick = { asking = Removal(selected, oneName) },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.testTag("today_nature_delete"),
                        ) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.today_nature_delete)) }
                    } else {
                        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                        Text(stringResource(R.string.today_nature_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (!l.isNullOrEmpty()) {
                            TextButton(onClick = { selecting = true }, modifier = Modifier.testTag("today_nature_select")) { Text(stringResource(R.string.today_nature_select)) }
                        }
                    }
                }
                when {
                    l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Leaf, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    }
                    l.isEmpty() -> TodayEmpty("🌿", stringResource(R.string.today_species_empty))
                    else -> {
                        val groups = l.groupingBy { it.group ?: "" }.eachCount().entries.sortedByDescending { it.value }
                        val shown = if (filter == null) l else l.filter { (it.group ?: "") == filter }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                        ) {
                            item(span = { GridItemSpan(maxLineSpan) }) { CollectionHero(l) }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                                    item { FilterPill(stringResource(R.string.today_nature_all) + " " + l.size, filter == null) { filter = null } }
                                    items(groups, key = { it.key }) { (g, n) ->
                                        FilterPill(TodayViewModel.speciesEmoji(g.ifEmpty { null }) + " " + groupLabel(g.ifEmpty { null }) + " " + n, filter == g) { filter = g }
                                    }
                                }
                            }
                            items(shown, key = { it.latin }) { c ->
                                val toggle = { selected = if (c.latin in selected) selected - c.latin else selected + c.latin }
                                SpeciesCard(
                                    c,
                                    selected = if (selecting) c.latin in selected else null,
                                    onClick = { if (selecting) toggle() else trail = trail + c.latin },
                                    onLongClick = { if (!selecting) { selecting = true; selected = setOf(c.latin) } else toggle() },
                                )
                            }
                        }
                    }
                }
            }
            val d = detail?.let { k -> l?.firstOrNull { it.latin == k } }
            AnimatedVisibility(d != null, enter = fadeIn(tween(200)) + scaleIn(tween(250), initialScale = 0.96f), exit = fadeOut(tween(150))) {
                var keep by remember { mutableStateOf(d) }
                if (d != null) keep = d
                keep?.let { sp ->
                    key(sp.latin) {
                        SpeciesPage(
                            vm, sp, l.orEmpty(),
                            onOpen = { trail = trail + it },
                            // Opened straight onto a kind (from the timeline or 探索): stepping out of it leaves the page.
                            onBack = { if (trail.size == 1 && open != null) onDismiss() else trail = trail.dropLast(1) },
                            onAsk = onAsk,
                            onRemove = { asking = it },
                        )
                    }
                }
            }
            asking?.let { r ->
                AlertDialog(
                    onDismissRequest = { asking = null },
                    title = {
                        Text(
                            when {
                                r.photo != null -> stringResource(R.string.today_nature_remove_photo_title)
                                r.name != null -> stringResource(R.string.today_nature_delete_one_title, r.name)
                                else -> stringResource(R.string.today_nature_delete_many_title, r.latins.size)
                            },
                        )
                    },
                    text = {
                        Text(
                            if (r.photo != null) stringResource(R.string.today_nature_remove_photo_body, r.name.orEmpty())
                            else stringResource(R.string.today_nature_delete_body),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { asking = null; remove(r) }, modifier = Modifier.testTag("today_nature_delete_confirm")) {
                            Text(stringResource(R.string.today_nature_delete_confirm), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(android.R.string.cancel)) } },
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))
        } }
    }
}

/** What "Remove?" is about: these kinds, or one [photo] of one kind; [name] when it is a single kind. */
private class Removal(val latins: Set<String>, val name: String?, val photo: CameraPhoto? = null)

@Composable
private fun CollectionHero(l: List<CollectedSpecies>) {
    val first = l.minBy { it.first.atMs }
    val since = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(first.first.atMs).atZone(ZoneId.systemDefault()))
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${l.size}", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Leaf, lineHeight = 56.sp)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.today_nature_kinds), fontSize = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        Text(
            stringResource(R.string.today_nature_since, since, speciesName(first.name, first.latin)),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Leaf else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 13.sp, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SpeciesCard(c: CollectedSpecies, selected: Boolean?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shrink by animateFloatAsState(if (selected == true) 0.92f else 1f, label = "pick")
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.8f).scale(shrink).clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).testTag("today_species_card"),
    ) {
        PhotoImage(c.photos.firstOrNull(), Modifier.fillMaxSize(), size = 600, fallback = TodayViewModel.speciesEmoji(c.group))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xB3000000))))
        Text(
            "No.${c.number}",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(10.dp).clip(RoundedCornerShape(50)).background(Color(0x59000000)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
        if (c.records.size > 1 && selected == null) {
            Text(
                "×${c.records.size}",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(50)).background(Color(0x59000000)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(speciesName(c.name, c.latin), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.latin, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected != null) {
            if (!selected) Box(Modifier.fillMaxSize().background(Color(0x33000000)))
            Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(24.dp).clip(CircleShape)
                    .background(if (selected) Leaf else Color(0x59000000))
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
    }
}

/**
 * One species (§17.4): the user's photos large; what it is (names, how
 * common, the ranks above it); a Wikipedia summary; when in the year it is
 * about, with the user's own months lit; what it is easily mistaken for; the
 * user's history with it; and what else of its family they know.
 */
@Composable
private fun SpeciesPage(
    vm: TodayViewModel,
    c: CollectedSpecies,
    all: List<CollectedSpecies>,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onAsk: (String, List<String>) -> Unit,
    onRemove: (Removal) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val whenFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    BackHandler(onBack = onBack)
    val profile by produceState<SpeciesId.Profile?>(null, c.latin) { value = vm.speciesProfile(c.latin) }
    val info by produceState<SpeciesInfo.Info?>(null, profile) { profile?.taxon?.let { value = vm.speciesInfo(it) } }
    val firstPlace by produceState(c.first.place, c.latin) {
        if (value == null) value = c.first.photos.firstOrNull()?.let { vm.placeOf(it) }
    }
    val family by produceState(emptyList<CollectedSpecies>(), c.latin, all) { value = vm.sameFamily(c.latin, all) }
    val myMonths = remember(c) { c.records.map { Instant.ofEpochMilli(it.atMs).atZone(zone).monthValue }.toSet() }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag("today_species_page")) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.9f)) {
                val photos = c.photos
                val pager = rememberPagerState { photos.size }
                if (photos.isEmpty()) {
                    PhotoImage(null, Modifier.fillMaxSize(), fallback = TodayViewModel.speciesEmoji(c.group))
                } else {
                    HorizontalPager(pager, Modifier.fillMaxSize()) { i -> PhotoImage(photos[i], Modifier.fillMaxSize(), size = 1200) }
                    if (photos.size > 1) {
                        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(photos.size) { i ->
                                Box(Modifier.size(if (i == pager.currentPage) 8.dp else 6.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == pager.currentPage) 1f else 0.5f)))
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(Color(0x80000000), Color.Transparent))))
                IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                }
                // Managing (§17.4): this photo, or the whole kind.
                var menu by remember { mutableStateOf(false) }
                val name = speciesName(c.name, c.latin)
                Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(4.dp)) {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("today_species_more")) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = Color.White)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (photos.size > 1) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.today_nature_remove_photo)) },
                                onClick = { menu = false; onRemove(Removal(setOf(c.latin), name, photos[pager.currentPage])) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.today_nature_delete), color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onRemove(Removal(setOf(c.latin), name)) },
                            modifier = Modifier.testTag("today_species_delete"),
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding()) {
                // ── What it is ──
                Text(speciesName(c.name, c.latin), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(c.latin, fontSize = 15.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(TodayViewModel.speciesEmoji(c.group) + " " + groupLabel(c.group))
                    c.genus?.let { Tag(it) }
                    Tag("No.${c.number}")
                    profile?.taxon?.count?.takeIf { it > 0 }?.let { Tag(commonness(it)) }
                }
                profile?.taxon?.chain?.takeIf { it.isNotEmpty() }?.let { chain ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        (chain.map { it.second ?: it.first } + (c.genus ?: "")).filter { it.isNotBlank() }.joinToString("  ›  "),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        maxLines = 1,
                    )
                }

                // ── About it (Wikipedia) ──
                Section(stringResource(R.string.today_nature_know))
                AboutCard(info)

                // ── When to see it ──
                info?.months?.let { months ->
                    Section(stringResource(R.string.today_nature_when))
                    MonthChart(months, myMonths)
                    Spacer(Modifier.height(8.dp))
                    val peak = months.indices.filter { months[it] >= months.max() * 0.85 }
                        .let { ms -> monthNames(ms.map { it + 1 }) }
                    Text(
                        stringResource(R.string.today_nature_when_peak, peak, info?.sightings ?: 0),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (myMonths.isNotEmpty()) {
                        Text(stringResource(R.string.today_nature_when_you), fontSize = 12.sp, color = Leaf)
                    }
                }

                // ── Easily mistaken for ──
                profile?.lookalikes?.takeIf { it.isNotEmpty() }?.let { looks ->
                    Section(stringResource(R.string.today_nature_lookalikes))
                    val mine = all.map { it.latin }.toSet()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        looks.forEach { t -> LookalikeChip(t, collected = t.latin in mine, onClick = { onOpen(t.latin) }) }
                    }
                    if (looks.any { it.latin in mine }) {
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.today_nature_lookalikes_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // ── You and it ──
                Section(stringResource(R.string.today_nature_you))
                val first = c.first
                InfoLine(stringResource(R.string.today_nature_first_met), whenFmt.format(Instant.ofEpochMilli(first.atMs).atZone(zone)) + (firstPlace?.let { " · $it" } ?: ""))
                InfoLine(stringResource(R.string.today_nature_times_label), pluralStringResource(R.plurals.today_nature_times, c.records.size, c.records.size))
                InfoLine(
                    stringResource(R.string.today_nature_months_seen),
                    monthNames(myMonths.sorted()),
                )
                Spacer(Modifier.height(8.dp))
                c.records.asReversed().forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        PhotoImage(r.photos.firstOrNull(), Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)), size = 200, fallback = TodayViewModel.speciesEmoji(c.group))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(whenFmt.format(Instant.ofEpochMilli(r.atMs).atZone(zone)) + (r.place?.let { " · $it" } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (r.body.isNotBlank()) Text(r.body, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // ── Its family ──
                if (family.isNotEmpty()) {
                    Section(stringResource(R.string.today_nature_family))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(family, key = { it.latin }) { f ->
                            Column(Modifier.width(92.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpen(f.latin) }) {
                                PhotoImage(f.photos.firstOrNull(), Modifier.size(92.dp).clip(RoundedCornerShape(12.dp)), size = 300, fallback = TodayViewModel.speciesEmoji(f.group))
                                Text(speciesName(f.name, f.latin), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                val askText = stringResource(R.string.today_nature_ask_text, speciesName(c.name, c.latin), c.latin)
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LeafSoft)
                        .clickable { onAsk(askText, c.photos.take(1).map { it.uri }) }
                        .padding(vertical = 14.dp).testTag("today_species_ask"),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Forum, null, tint = Leaf, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.today_nature_ask), color = Leaf, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** How common, in words, from its sightings in China on iNaturalist (median 11; top 10% ≥ 117; top 1% ≥ 733). */
@Composable
private fun commonness(count: Int): String = stringResource(
    when {
        count >= 700 -> R.string.today_common_very
        count >= 100 -> R.string.today_common
        count >= 20 -> R.string.today_common_un
        else -> R.string.today_common_rare
    },
)

/** "4月、5月" / "Apr, May": short month names (1–12), listed the local way. */
private fun monthNames(months: List<Int>): String {
    val locale = java.util.Locale.getDefault()
    val sep = if (locale.language == "zh") "、" else ", "
    return months.joinToString(sep) { java.time.Month.of(it).getDisplayName(java.time.format.TextStyle.SHORT, locale) }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(26.dp))
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
}

/**
 * The Wikipedia summary, five lines until tapped, and below it a row that opens
 * the article in the app — or Wikipedia's search for the name when there is no
 * article ([T-today-wiki-link]: the link used to go through an ambient nothing
 * provided, so it never opened).
 */
@Composable
private fun AboutCard(info: SpeciesInfo.Info?) {
    var open by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<String?>(null) }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(14.dp)) {
                val text = info?.summary
                when {
                    // Read from a local file in a moment: nothing to say meanwhile.
                    info == null -> Unit
                    text == null -> Text(stringResource(R.string.today_nature_wiki_none), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        if (info.aboutGenus) {
                            Text(stringResource(R.string.today_nature_genus_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(text, fontSize = 14.sp, lineHeight = 22.sp, maxLines = if (open) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (info != null) {
                Row(
                    Modifier.fillMaxWidth().clickable { reading = info.articleUrl ?: info.searchUrl }.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(if (info.articleUrl != null) R.string.today_nature_wiki_source else R.string.today_nature_wiki_search),
                        fontSize = 13.sp,
                        color = Leaf,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Leaf, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
    reading?.let { com.openminis.app.ui.components.UrlPreviewSheet(url = it, onDismiss = { reading = null }) }
}

/** Twelve bars, January to December; the months the user met it are green. */
@Composable
private fun MonthChart(months: List<Int>, mine: Set<Int>) {
    val max = months.max().coerceAtLeast(1)
    val locale = java.util.Locale.getDefault()
    Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        months.forEachIndexed { i, v ->
            val lit = (i + 1) in mine
            Column(Modifier.weight(1f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                val h by animateFloatAsState((v.toFloat() / max).coerceAtLeast(0.03f), tween(600 + i * 30), label = "m$i")
                Box(
                    Modifier.fillMaxWidth().weight(1f, fill = false).fillMaxSize(h).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (lit) Leaf else MaterialTheme.colorScheme.outlineVariant),
                )
                Text(
                    java.time.Month.of(i + 1).getDisplayName(java.time.format.TextStyle.NARROW, locale),
                    fontSize = 10.sp,
                    color = if (lit) Leaf else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** A look-alike; green and tappable if it is in the collection too. */
@Composable
private fun LookalikeChip(t: SpeciesId.Taxon, collected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (collected) LeafSoft else MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (collected) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(t.zh ?: t.latin, fontSize = 14.sp, color = if (collected) Leaf else MaterialTheme.colorScheme.onSurface)
        Text(t.latin, fontSize = 11.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Tag(s: String) {
    Text(
        s,
        fontSize = 12.sp,
        color = Leaf,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(LeafSoft).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}
