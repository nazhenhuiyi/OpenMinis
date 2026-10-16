package com.openminis.app.ui.today

import androidx.compose.foundation.border
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.R
import com.openminis.app.data.photos.CameraPhoto
import kotlinx.coroutines.awaitCancellation
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

// [T-today-explore] The 探索 tab (SPEC §20): today's page from the past, the
// two collections, firsts, and small findings — all from the author's own days.

private val Gold = Color(0xFFB8925A)
private val GoldSoft = Color(0x1FB8925A)
private val KitchenSoft = Kitchen.copy(alpha = 0.14f)

/** A page opened over 探索. */
private sealed interface Sheet {
    data class Nature(val latin: String?) : Sheet
    data class Dishes(val focus: DishFocus?) : Sheet
    /** "That day" (§20.1): every note of the page's day. */
    data class Day(val page: ExplorePage) : Sheet
    /** 心愿单 in full. */
    data object Wishes : Sheet
}

/** The colour of wishes: something still ahead. */
private val Wishful = Color(0xFF8E7CC3)
private val WishfulSoft = Color(0x268E7CC3)

/** [onAskMinis] as on Today: the nature page can hand a species to a new chat. */
@Composable
fun ExploreScreen(
    onAskMinis: (text: String, photoUris: List<String>) -> Unit,
    modifier: Modifier = Modifier,
    // Today's: the nature collection and 我的菜单 open on it, and 探索 reads from it.
    today: TodayViewModel = viewModel(),
    vm: ExploreViewModel = viewModel(factory = viewModelFactory { initializer { ExploreViewModel(today.getApplication(), today) } }),
) {
    val zone = ZoneId.systemDefault()
    val data by vm.data.collectAsState()
    val page by vm.page.collectAsState()
    // Saved with the tab (AppNavigation keeps each tab's saved state), so coming back finds the same place.
    val listState = rememberLazyListState()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var viewer by remember { mutableStateOf<Pair<List<CameraPhoto>, Int>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    // Ticking a wish off or letting it go happens at once, with a moment to take it back.
    val onWish = rememberWishSetter(snackbar, vm::setWish)
    val askWish: (Wish) -> Unit = { w -> sheet = null; onAskMinis(context.getString(R.string.explore_wish_ask_prompt, w.text), emptyList()) }

    // Back on 探索 goes to Today first, like any tab beside the home one.
    // Composed after the NavHost and AppNavigation's own, so it is asked first.
    BackHandler { MainTabs.select(MainTabs.Tab.TODAY) }

    // Built only while on screen: resumed and this tab showing (leaving the tab disposes this).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.setVisible(true)
            try { awaitCancellation() } finally { vm.setVisible(false) }
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.tab_explore), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.explore_tagline), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
            // Only the very first time is there anything to wait for: the page's
            // shapes hold still, then everything fades in at once.
            AnimatedContent(
                targetState = data,
                contentKey = { it != null },
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
                label = "explore",
            ) { d ->
                if (d == null) Placeholder()
                else ExploreContent(
                    d = d,
                    page = page,
                    onPage = vm::turnTo,
                    listState = listState,
                    zone = zone,
                    onSheet = { sheet = it },
                    onPhoto = { list, i -> viewer = list to i },
                    onWish = onWish,
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
      }
    }

    when (val sh = sheet) {
        is Sheet.Nature -> NatureCollectionPage(
            vm = today,
            open = sh.latin,
            onAsk = { text, uris -> sheet = null; onAskMinis(text, uris) },
            onDismiss = { sheet = null },
        )
        is Sheet.Dishes -> DishesPage(today, focus = sh.focus, initial = data?.food, onDismiss = { sheet = null })
        is Sheet.Day -> DayPage(vm, sh.page, zone, onPhoto = { list, i -> viewer = list to i }, onDismiss = { sheet = null })
        Sheet.Wishes -> WishesPage(data?.wishes.orEmpty(), vm::setWish, onAsk = askWish, onDismiss = { sheet = null })
        null -> Unit
    }
    // After the day page, so a photo opened from it shows above it.
    viewer?.let { (list, start) -> PhotoViewer(list, start, zone, onDismiss = { viewer = null }) }
}

@Composable
private fun ExploreContent(
    d: ExploreData,
    page: Int,
    onPage: (Int) -> Unit,
    listState: LazyListState,
    zone: ZoneId,
    onSheet: (Sheet) -> Unit,
    onPhoto: (List<CameraPhoto>, Int) -> Unit,
    onWish: (Wish, String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp)) {
        item(key = "page") {
            PageCard(
                pages = d.pages,
                index = page.coerceIn(0, (d.pages.size - 1).coerceAtLeast(0)),
                photos = d.photos,
                zone = zone,
                onIndex = onPage,
                onPhoto = onPhoto,
                onOpenDay = { onSheet(Sheet.Day(it)) },
            )
        }
        item(key = "tiles") {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(
                    title = stringResource(R.string.explore_nature),
                    sub = if (d.speciesCount == 0) stringResource(R.string.explore_nature_empty) else stringResource(R.string.explore_nature_count, d.speciesCount),
                    cover = d.natureCover,
                    emoji = "🌿",
                    tint = LeafSoft,
                    modifier = Modifier.weight(1f),
                ) { onSheet(Sheet.Nature(null)) }
                if (d.showFood) {
                    Tile(
                        title = stringResource(R.string.explore_food),
                        sub = if (d.food.isEmpty()) stringResource(R.string.explore_food_empty)
                        else stringResource(R.string.explore_food_count, d.food.size),
                        cover = d.foodCover,
                        emoji = "🍳",
                        tint = KitchenSoft,
                        modifier = Modifier.weight(1f),
                        onClick = { onSheet(Sheet.Dishes(null)) },
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        val open = d.wishes.filter { it.open }
        if (d.wishes.isNotEmpty()) {
            item(key = "wishes-h") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    SectionTitle(stringResource(R.string.explore_wishes), Modifier.weight(1f))
                    Text(
                        stringResource(R.string.explore_wishes_all) + " ›",
                        fontSize = 13.sp,
                        color = Wishful,
                        modifier = Modifier.padding(bottom = 10.dp).clip(RoundedCornerShape(50))
                            .clickable { onSheet(Sheet.Wishes) }.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            item(key = "wishes-sub") {
                MetaText(wishesSummary(open.size, d.wishes.count { it.done }))
            }
            for (w in open.take(3)) {
                item(key = "wish:${w.note.id}") {
                    WishRow(w, zone, onDone = { onWish(w, Explore.WISH_DONE) }) { onSheet(Sheet.Day(ExplorePage(w.note, null))) }
                }
            }
        }
        if (d.firsts.isNotEmpty()) {
            item(key = "firsts-h") { SectionTitle(stringResource(R.string.explore_firsts)) }
            item(key = "firsts") {
                FirstsStrip(d.firsts, d.photos, zone) { f ->
                    onSheet(if (f.species) Sheet.Nature(f.key) else Sheet.Dishes(f.noteId?.let { DishFocus(it, f.key) }))
                }
            }
        }
        d.facts?.let { fx ->
            item(key = "facts-h") { SectionTitle(stringResource(R.string.explore_findings)) }
            item(key = "facts") { FactsGrid(fx) }
        }
    }
}

/** The page's shapes, quiet, for the first load only. */
@Composable
private fun Placeholder() {
    val c = MaterialTheme.colorScheme.surfaceContainerLow
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(22.dp)).background(c))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).height(150.dp).clip(RoundedCornerShape(18.dp)).background(c))
            Box(Modifier.weight(1f).height(150.dp).clip(RoundedCornerShape(18.dp)).background(c))
        }
    }
}

// ───────────────────────────── Today's page ─────────────────────────────

@Composable
private fun PageCard(
    pages: List<ExplorePage>,
    index: Int,
    photos: Map<String, CameraPhoto>,
    zone: ZoneId,
    onIndex: (Int) -> Unit,
    onPhoto: (List<CameraPhoto>, Int) -> Unit,
    onOpenDay: (ExplorePage) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val go: (Int) -> Unit = { i ->
        if (i in pages.indices && i != index) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onIndex(i)
        }
    }
    val swipe = with(LocalDensity.current) { 56.dp.toPx() }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(GoldSoft, MaterialTheme.colorScheme.surfaceContainerLow)))
            // Swipe left for the next page, right for the one before — like turning paper.
            .pointerInput(index, pages.size) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = { if (dx < -swipe) go(index + 1) else if (dx > swipe) go(index - 1) },
                ) { _, amount -> dx += amount }
            }
            .padding(18.dp),
    ) {
        if (pages.isEmpty()) {
            Kicker(stringResource(R.string.explore_today_page))
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.explore_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            return@Column
        }
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (fadeIn(tween(220, delayMillis = 60)) + slideInHorizontally(tween(280)) { dir * it / 5 }) togetherWith
                    (fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { -dir * it / 8 }) using SizeTransform(clip = false)
            },
            label = "explore-page",
        ) { i ->
            val p = pages[i]
            val shown = p.note.photos.mapNotNull(photos::get)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenDay(p) },
            ) {
                Kicker(kickerText(p))
                Spacer(Modifier.height(4.dp))
                MetaText(dayLabel(p.note.date) + "  ·  " + clockTime(p.note.atMs, zone) + (p.note.place?.let { "  ·  $it" } ?: ""))
                if (p.note.body.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        p.note.body,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp),
                        maxLines = 9,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (shown.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    PhotoStrip(shown, onPhoto, corner = 12.dp, size = 500)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The day's pages as dots: tap one to go back to it (a larger target than the dot itself).
            for (i in pages.indices) {
                val w by animateDpAsState(if (i == index) 18.dp else 6.dp, label = "dot")
                Box(Modifier.clip(CircleShape).clickable { go(i) }.padding(horizontal = 3.dp, vertical = 8.dp)) {
                    Box(
                        Modifier.height(6.dp).width(w).clip(CircleShape)
                            .background(if (i == index) Gold else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            val more = index < pages.lastIndex
            Text(
                stringResource(if (more) R.string.explore_flip else R.string.explore_flip_done),
                fontSize = 13.sp,
                fontWeight = if (more) FontWeight.Medium else FontWeight.Normal,
                color = if (more) Gold else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .then(if (more) Modifier.background(GoldSoft).clickable { go(index + 1) } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun kickerText(p: ExplorePage): String = when (val m = p.monthsAgo) {
    null -> stringResource(R.string.explore_random)
    1 -> stringResource(R.string.explore_one_month_ago)
    6 -> stringResource(R.string.explore_half_year_ago)
    12 -> stringResource(R.string.explore_one_year_ago)
    else -> if (m % 12 == 0) stringResource(R.string.explore_years_ago, m / 12) else stringResource(R.string.explore_months_ago, m)
}

@Composable
private fun Kicker(text: String) =
    Text("✦  $text", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Medium)

// ───────────────────────────── Collections ─────────────────────────────

/** Gives a little under the finger, so a tap feels like pressing something. */
@Composable
private fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, animationSpec = tween(120), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }.clickable(source, LocalIndication.current, onClick = onClick)
}

@Composable
private fun Tile(title: String, sub: String, cover: CameraPhoto?, emoji: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(150.dp).pressable(onClick).clip(RoundedCornerShape(18.dp)).background(tint)) {
        if (cover != null) {
            PhotoImage(cover, Modifier.fillMaxSize(), size = 500)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xAA000000))))
        } else {
            Text(emoji, fontSize = 34.sp, modifier = Modifier.align(Alignment.TopEnd).padding(14.dp))
        }
        val ink = if (cover != null) Color.White else MaterialTheme.colorScheme.onSurface
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(title, color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = ink.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(top = 26.dp, bottom = 10.dp))

/** Firsts as a strip of small cards: the photo from that first time, the name, and when. */
@Composable
private fun FirstsStrip(firsts: List<ExploreFirst>, photos: Map<String, CameraPhoto>, zone: ZoneId, onOpen: (ExploreFirst) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        // Bleed to the screen edges while the cards line up with the page.
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = Modifier.fillMaxWidth().layout { m, c ->
            val extra = 40.dp.roundToPx()
            val p = m.measure(c.copy(maxWidth = c.maxWidth + extra))
            layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
        },
    ) {
        items(firsts, key = { "${it.species}:${it.key}" }) { f ->
            Column(Modifier.width(118.dp).pressable { onOpen(f) }) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))
                        .background(if (f.species) LeafSoft else KitchenSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    val ph = f.photo?.let(photos::get)
                    if (ph != null) PhotoImage(ph, Modifier.fillMaxSize(), size = 360)
                    else Text(if (f.species) "🌿" else "🍳", fontSize = 30.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (f.species) R.string.explore_first_saw else R.string.explore_first_written) + " · " +
                        shortDate(Instant.ofEpochMilli(f.atMs).atZone(zone).toLocalDate()),
                    fontSize = 11.sp,
                    color = if (f.species) Leaf else Kitchen,
                    maxLines = 1,
                )
            }
        }
    }
}

// ───────────────────────────── Findings ─────────────────────────────

@Composable
private fun FactsGrid(f: ExploreFacts) {
    val cells = listOf(
        f.notes.toString() to stringResource(R.string.explore_fact_notes, shortDate(f.since)),
        bigNumber(f.chars) to stringResource(R.string.explore_fact_chars),
        f.longestStreak.toString() to stringResource(R.string.explore_fact_streak),
        stringResource(timeName(f.favouriteTime)) to stringResource(R.string.explore_fact_time, f.favouriteTimePercent),
        shortDate(f.busiestDay) to stringResource(R.string.explore_fact_busiest, f.busiestCount),
        f.photos.toString() to stringResource(R.string.explore_fact_photos),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in cells.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((value, label) in row) {
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun timeName(t: TimeOfDay): Int = when (t) {
    TimeOfDay.DAWN -> R.string.explore_time_dawn
    TimeOfDay.MORNING -> R.string.explore_time_morning
    TimeOfDay.AFTERNOON -> R.string.explore_time_afternoon
    TimeOfDay.EVENING -> R.string.explore_time_evening
    TimeOfDay.NIGHT -> R.string.explore_time_night
}

/** 41234 → "4.1万" in Chinese, "41,234" elsewhere. */
private fun bigNumber(n: Int): String {
    val locale = Locale.getDefault()
    return if (locale.language == "zh" && n >= 10_000) String.format(locale, "%.1f万", n / 10_000.0)
    else java.text.NumberFormat.getIntegerInstance(locale).format(n)
}


// ───────────────────────────── That day ─────────────────────────────

/**
 * The page's whole day, opened from the card: every note, words in full and
 * all photos, so a page turned up can be read where it came from. The page's
 * own note is marked.
 */
@Composable
private fun DayPage(vm: ExploreViewModel, page: ExplorePage, zone: ZoneId, onPhoto: (List<CameraPhoto>, Int) -> Unit, onDismiss: () -> Unit) {
    var loaded by remember { mutableStateOf<Pair<List<ExploreNote>, Map<String, CameraPhoto>>?>(null) }
    LaunchedEffect(page.note.date) { loaded = vm.day(page.note.date) }
    FullScreenPage(
        onDismiss,
        header = {
            Column {
                PageTitle(dayLabel(page.note.date))
                if (page.monthsAgo != null) Text(kickerText(page), fontSize = 12.sp, color = Gold)
            }
        },
    ) {
        val list = loaded?.first.orEmpty()
        val photos = loaded?.second.orEmpty()
        val listState = rememberLazyListState()
        // Open on the page's own note.
        LaunchedEffect(loaded) {
            val i = list.indexOfFirst { it.id == page.note.id }
            if (i > 0) listState.scrollToItem(i)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            items(list, key = { it.id }) { n ->
                val mine = n.id == page.note.id
                val shown = n.photos.mapNotNull(photos::get)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp)) {
                    // The page's own note carries a thin gold rule.
                    Box(Modifier.width(3.dp).height(if (mine) 22.dp else 0.dp).clip(CircleShape).background(Gold))
                    Spacer(Modifier.width(if (mine) 10.dp else 0.dp))
                    Column(Modifier.weight(1f)) {
                        MetaText(clockTime(n.atMs, zone) + (n.place?.let { "  ·  $it" } ?: ""))
                        if (n.body.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(n.body, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp))
                        }
                        if (shown.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            PhotoStrip(shown, onPhoto, max = null)
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────────── 心愿单 ─────────────────────────────

/**
 * One wish: a ring to tick it off (it fills, then the row goes), the sentence
 * that says it, when it was written — or, once done, when it came true.
 */
@Composable
private fun WishRow(
    w: Wish,
    zone: ZoneId,
    onDone: (() -> Unit)?,
    onUndo: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
) {
    var ticking by remember(w.note.id, w.done) { mutableStateOf(false) }
    val fill by animateFloatAsState(if (w.done || ticking) 1f else 0f, tween(260), label = "tick")
    LaunchedEffect(ticking) {
        if (ticking) { delay(380); onDone?.invoke() }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.padding(top = 1.dp).size(24.dp).clip(CircleShape)
                .background(Leaf.copy(alpha = fill))
                .border(1.5.dp, if (fill > 0f) Leaf else Wishful, CircleShape)
                .then(
                    when {
                        !w.done && onDone != null -> Modifier.clickable { ticking = true }
                        w.done && onUndo != null -> Modifier.clickable(onClick = onUndo)
                        else -> Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (fill > 0.5f) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(w.text, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            val written = shortDate(w.note.date)
            if (w.done) Text(
                stringResource(R.string.explore_wish_done_on, w.note.wishOn?.let(::shortDate) ?: written),
                fontSize = 12.sp, color = Leaf,
            ) else MetaText(written)
            extra?.invoke()
        }
    }
}

/**
 * Ticking a wish off or letting it go: at once, with a nudge for "done", and a
 * snackbar that can take it back.
 */
@Composable
private fun rememberWishSetter(snackbar: SnackbarHostState, onSet: (ExploreNote, String?) -> Unit): (Wish, String) -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    return { w, state ->
        if (state == Explore.WISH_DONE) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onSet(w.note, state)
        scope.offerUndo(
            snackbar,
            context.getString(if (state == Explore.WISH_DONE) R.string.explore_wish_done_snack else R.string.explore_wish_dropped_snack),
            context.getString(R.string.today_undo),
        ) { onSet(w.note, null) }
    }
}

/** "还有 3 件想做的事", or once none is left, "都做到了，一共 5 件". */
@Composable
private fun wishesSummary(open: Int, done: Int): String =
    if (open == 0) stringResource(R.string.explore_wishes_all_done, done) else stringResource(R.string.explore_wishes_count, open)

/** 心愿单 in full: what is still wished for, each with "look into it" and "let go", then what came true. */
@Composable
private fun WishesPage(wishes: List<Wish>, onSet: (ExploreNote, String?) -> Unit, onAsk: (Wish) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val snackbar = remember { SnackbarHostState() }
    val set = rememberWishSetter(snackbar, onSet)
    val open = wishes.filter { it.open }
    val done = wishes.filter { it.done }
    FullScreenPage(onDismiss, header = { PageTitle(stringResource(R.string.explore_wishes)) }) {
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 80.dp)) {
                item { MetaText(wishesSummary(open.size, done.size)) }
                items(open, key = { it.note.id }) { w ->
                    WishRow(
                        w, zone,
                        onDone = { set(w, Explore.WISH_DONE) },
                        extra = {
                            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WishAction(stringResource(R.string.explore_wish_ask), Wishful, WishfulSoft) { onAsk(w) }
                                WishAction(stringResource(R.string.explore_wish_drop), MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh) {
                                    set(w, Explore.WISH_DROPPED)
                                }
                            }
                        },
                    )
                }
                if (done.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.explore_wishes_done_title)) }
                    // Tapping a done one's ring puts it back on the list.
                    items(done, key = { "done:" + it.note.id }) { w -> WishRow(w, zone, onDone = null, onUndo = { onSet(w.note, null) }) }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun WishAction(label: String, ink: Color, bg: Color, onClick: () -> Unit) =
    Text(
        label, fontSize = 12.sp, color = ink,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
    )
