package com.openminis.app.ui.today

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Forum
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.openminis.app.R
import com.openminis.app.data.photos.CameraPhoto
import com.openminis.app.data.species.SpeciesId
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// [T-today-species] Everything the user sees of species ID (SPEC §17):
// the suggestion under a photo group, the "Identify" sheet, the little
// celebration after recording, and the nature collection pages.

/** Soft greens for the nature pages, readable on light and dark backgrounds alike. */
private val Leaf = Color(0xFF3F7D4E)
private val LeafSoft = Color(0x1F3F7D4E)

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
internal fun speciesName(name: String, latin: String): String =
    if (' ' !in latin.trim()) stringResource(R.string.today_species_genus, name) else name

/** "Very likely X" / "Looks like X" / "a kind of X": how sure, in words rather than percentages. */
@Composable
private fun guessPhrase(name: String, genus: Boolean, prob: Float): String {
    val shown = if (genus) stringResource(R.string.today_species_genus, name) else name
    return if (prob >= 0.8f && !genus) stringResource(R.string.today_species_conf_high, shown)
    else stringResource(R.string.today_species_looks_like, shown)
}

@Composable
private fun PhotoImage(photo: CameraPhoto?, modifier: Modifier, size: Int = 600, fallback: String = "✦") {
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
 * group of nine it is clear which one. Tapping the words opens the other
 * candidates; Record takes it; × says no.
 */
@Composable
internal fun SpeciesSuggestionChip(photo: CameraPhoto, s: SpeciesSuggestion, onOpen: () -> Unit, onRecord: () -> Unit, onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(shown, enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { -it / 3 }) {
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
                    s.latin,
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Leaf).clickable(onClick = onRecord)
                    .padding(horizontal = 14.dp, vertical = 7.dp).testTag("today_species_record"),
            ) {
                Text(stringResource(R.string.today_species_record), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
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
 * which one. None right → ask Minis with the photo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IdentifySheet(
    vm: TodayViewModel,
    photos: List<CameraPhoto>,
    start: CameraPhoto?,
    tags: Map<String, com.openminis.app.data.species.PhotoTag>,
    onPick: (CameraPhoto, SpeciesSuggestion) -> Unit,
    onUntag: (CameraPhoto) -> Unit,
    onAsk: (CameraPhoto) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf(start) }
    val results = remember { mutableStateMapOf<String, SpeciesId.Result?>() }
    LaunchedEffect(Unit) { if (current == null) current = vm.bestPhoto(photos) }
    LaunchedEffect(current) {
        val p = current ?: return@LaunchedEffect
        if (!results.containsKey(p.relativePath)) results[p.relativePath] = vm.identify(p)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("today_identify_sheet")) {
            val p = current
            // Wide rather than tall, so the candidates are on screen without scrolling.
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp))) {
                PhotoImage(p, Modifier.fillMaxSize(), size = 1000)
                if (p == null || !results.containsKey(p.relativePath)) ScanSweep()
            }
            if (photos.size > 1) {
                LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(photos, key = { it.id }) { ph ->
                        val sel = ph.id == p?.id
                        PhotoImage(
                            ph,
                            Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                                .border(if (sel) 2.dp else 0.dp, if (sel) Leaf else Color.Transparent, RoundedCornerShape(10.dp))
                                .graphicsLayer { alpha = if (sel) 1f else 0.6f }
                                .clickable { current = ph },
                            size = 200,
                        )
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
                r == null || r.top.isEmpty() -> Text(stringResource(R.string.today_identify_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text(
                        stringResource(if (r.looksLikeOrganism) R.string.today_identify_title else R.string.today_identify_not_life),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    r.top.forEachIndexed { i, g -> CandidateCard(g, first = i == 0 && r.looksLikeOrganism, onClick = { onPick(p, SpeciesSuggestion.of(g)) }) }
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
private fun CandidateCard(g: SpeciesId.Guess, first: Boolean, onClick: () -> Unit) {
    val bar by animateFloatAsState(g.prob.coerceIn(0.02f, 1f), tween(700), label = "bar")
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (first) LeafSoft else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (first) BorderStroke(1.dp, Leaf.copy(alpha = 0.4f)) else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).testTag("today_identify_option"),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(TodayViewModel.speciesEmoji(g.taxon.group), fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (g.genus) stringResource(R.string.today_species_genus, g.name) else g.name,
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

// ───────────────────────── After recording (§17.2) ─────────────────────────

/**
 * Rises from the bottom for a moment: "New to your collection · No. 12" with
 * a bouncing emoji, or "Seen again · 3 times". Tap to open that species.
 */
@Composable
internal fun CollectedBanner(event: TodayViewModel.Event.Collected?, onOpen: (String) -> Unit, onGone: () -> Unit, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf(event) }
    if (event != null) last = event
    LaunchedEffect(event) { if (event != null) { delay(3200); onGone() } }
    AnimatedVisibility(
        visible = event != null,
        enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
        exit = slideOutVertically(tween(250)) { it } + fadeOut(),
        modifier = modifier,
    ) {
        val e = last ?: return@AnimatedVisibility
        val pop = remember { androidx.compose.animation.core.Animatable(0.3f) }
        LaunchedEffect(e) { pop.snapTo(0.3f); pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessLow)) }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Leaf,
            shadowElevation = 8.dp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .clickable { onOpen(e.latin) }.testTag("today_collected_banner"),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                    Text(TodayViewModel.speciesEmoji(e.group), fontSize = 24.sp, modifier = Modifier.scale(pop.value))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (e.times == 1) stringResource(R.string.today_collected_new, e.number) else stringResource(R.string.today_collected_again, e.times),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                    )
                    Text(speciesName(e.name, e.latin), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(stringResource(R.string.today_collected_total, e.total), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            }
        }
    }
}

// ───────────────────────── Nature collection (§17.4) ─────────────────────────

/**
 * Full-screen collection: how many kinds met, a filter by kind of life, and a
 * wall of photo cards numbered in the order they were first met. A card opens
 * the species page. [open] jumps straight to one species (from the banner or
 * a note's species line).
 */
@Composable
internal fun NatureCollectionPage(vm: TodayViewModel, open: String?, onAsk: (String, List<String>) -> Unit, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf<List<CollectedSpecies>?>(null) }
    var filter by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf(open) }
    LaunchedEffect(Unit) { list = vm.collection() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Status bar icons: dark on the light grid, light over a species photo.
        val view = androidx.compose.ui.platform.LocalView.current
        val lightBg = MaterialTheme.colorScheme.background.luminance() > 0.5f
        LaunchedEffect(detail, lightBg) {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = lightBg && detail == null
            }
        }
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag("today_collection")) {
            val l = list
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                    Text(stringResource(R.string.today_nature_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                when {
                    l == null -> Unit
                    l.isEmpty() -> EmptyCollection()
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
                            items(shown, key = { it.latin }) { c -> SpeciesCard(c, onClick = { detail = c.latin }) }
                        }
                    }
                }
            }
            val d = detail?.let { k -> l?.firstOrNull { it.latin == k } }
            AnimatedVisibility(d != null, enter = fadeIn(tween(200)) + scaleIn(tween(250), initialScale = 0.96f), exit = fadeOut(tween(150))) {
                var keep by remember { mutableStateOf(d) }
                if (d != null) keep = d
                keep?.let { SpeciesPage(it, onBack = { detail = null }, onAsk = onAsk) }
            }
        }
    }
}

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
private fun SpeciesCard(c: CollectedSpecies, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).testTag("today_species_card")) {
        PhotoImage(c.photos.firstOrNull(), Modifier.fillMaxSize(), size = 600, fallback = TodayViewModel.speciesEmoji(c.group))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xB3000000))))
        Text(
            "No.${c.number}",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(10.dp).clip(RoundedCornerShape(50)).background(Color(0x59000000)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
        if (c.records.size > 1) {
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
    }
}

@Composable
private fun EmptyCollection() {
    Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("🌿", fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.today_species_empty), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
    }
}

/** One species: its photos large, what it is, when first met, and every time since. */
@Composable
private fun SpeciesPage(c: CollectedSpecies, onBack: () -> Unit, onAsk: (String, List<String>) -> Unit) {
    val zone = ZoneId.systemDefault()
    val whenFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    androidx.activity.compose.BackHandler(onBack = onBack)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag("today_species_page")) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.9f)) {
                val photos = c.photos
                if (photos.isEmpty()) {
                    PhotoImage(null, Modifier.fillMaxSize(), fallback = TodayViewModel.speciesEmoji(c.group))
                } else {
                    val pager = rememberPagerState { photos.size }
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
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding()) {
                Text(speciesName(c.name, c.latin), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(c.latin, fontSize = 15.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(TodayViewModel.speciesEmoji(c.group) + " " + groupLabel(c.group))
                    c.genus?.let { Tag(it) }
                    Tag("No.${c.number}")
                }
                Spacer(Modifier.height(20.dp))
                val first = c.first
                InfoLine(stringResource(R.string.today_nature_first_met), whenFmt.format(Instant.ofEpochMilli(first.atMs).atZone(zone)) + (first.place?.let { " · $it" } ?: ""))
                InfoLine(stringResource(R.string.today_nature_times_label), androidx.compose.ui.res.pluralStringResource(R.plurals.today_nature_times, c.records.size, c.records.size))
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.today_nature_records), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                c.records.asReversed().forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PhotoImage(r.photos.firstOrNull(), Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)), size = 200, fallback = TodayViewModel.speciesEmoji(c.group))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(whenFmt.format(Instant.ofEpochMilli(r.atMs).atZone(zone)) + (r.place?.let { " · $it" } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (r.body.isNotBlank()) Text(r.body, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
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
