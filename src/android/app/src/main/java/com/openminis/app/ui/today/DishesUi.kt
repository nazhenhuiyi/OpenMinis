package com.openminis.app.ui.today

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.photos.CameraPhoto
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

// [T-today-murmur] 我的菜单 (SPEC §19.6): the murmurs about food, as they
// were written — the murmur is the thing, the dishes it names sit under it.

/** The kitchen's colour: made-at-home dishes here and on 探索. */
internal val Kitchen = Color(0xFFC98A4B)

/** Open 我的菜单 on one murmur ([noteId]) and the dish in it that was tapped. */
data class DishFocus(val noteId: String, val dish: String)

/** How many dishes were made, and how many tasted (made ones included): "做过 12 道 · 吃到 30 种". */
fun List<FoodNote>.dishCounts(): Pair<Int, Int> =
    flatMap { n -> n.dishes.filter { it.second }.map { it.first } }.toSet().size to
        flatMap { n -> n.dishes.map { it.first } }.toSet().size

/**
 * The menu page: "做过 16 道 · 吃到 31 种", a filter (自己做的 / 全部), then the
 * food murmurs newest first, each with its words, photos and dish names.
 * Murmurs never tagged are read while the page is open, so the feed fills in.
 */
@Composable
internal fun DishesPage(vm: TodayViewModel, focus: DishFocus? = null, onDismiss: () -> Unit) {
    // What 探索 last built shows at once; the page brings it up to date.
    var notes by remember { mutableStateOf(vm.explore.value?.food) }
    var photos by remember { mutableStateOf<Map<String, CameraPhoto>>(emptyMap()) }
    val gone = remember { HashSet<String>() }
    var homemadeOnly by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var viewer by remember { mutableStateOf<Pair<List<CameraPhoto>, Int>?>(null) }
    val listState = rememberLazyListState()
    // The focused murmur glows once it is in view, then the glow fades away.
    val glow = remember { Animatable(0f) }

    // One refresh at a time; asks while one runs fold into a single next one (a StateFlow keeps only the latest).
    val refreshes = remember { MutableStateFlow(0) }
    LaunchedEffect(Unit) {
        refreshes.collect {
            val list = vm.foodNotes()
            notes = list
            // Only photos not yet looked up (or known missing) go to the library.
            val missing = list.flatMap { it.photos }.filter { it !in photos && it !in gone }
            if (missing.isNotEmpty()) {
                val found = vm.photosFor(missing)
                gone += missing.filter { it !in found }
                photos = photos + found
            }
        }
    }
    LaunchedEffect(Unit) {
        var last = 0L
        vm.tagDishBacklog { done, total ->
            progress = if (done < total) done to total else null
            val now = System.currentTimeMillis()
            if (now - last > 2_000 || done == total) { last = now; refreshes.value++ }
        }
        progress = null
        refreshes.value++
    }

    val all = notes.orEmpty()
    val shown = remember(all, homemadeOnly) { if (homemadeOnly) all.filter { it.homemade } else all }
    val (made, tasted) = remember(all) { all.dishCounts() }
    val density = LocalDensity.current
    // Once the list is there: scroll so the focused murmur sits a little below the top, then glow.
    LaunchedEffect(notes != null) {
        if (focus == null || notes == null) return@LaunchedEffect
        val i = shown.indexOfFirst { it.id == focus.noteId }
        if (i < 0) return@LaunchedEffect
        listState.animateScrollToItem(i, scrollOffset = -with(density) { 24.dp.roundToPx() })
        glow.animateTo(1f, tween(280))
        delay(900)
        glow.animateTo(0f, tween(1400))
    }

    FullScreenPage(onDismiss, header = { PageTitle(stringResource(R.string.today_dishes_title)) }) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.today_dishes_summary, made, tasted), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.today_dishes_all), !homemadeOnly) { homemadeOnly = false }
                Pill(stringResource(R.string.today_dishes_homemade), homemadeOnly) { homemadeOnly = true }
            }
            progress?.let { (done, total) -> MetaText(stringResource(R.string.today_dishes_reading, done, total)) }
        }
        if (notes != null && shown.isEmpty() && progress == null) {
            Text(
                stringResource(R.string.today_dishes_empty),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            items(shown, key = { it.id }) { n ->
                val focused = n.id == focus?.noteId
                FoodNoteRow(
                    n,
                    n.photos.mapNotNull(photos::get),
                    // Read only while drawing: the glow never recomposes the row.
                    glow = if (focused) glow::value else null,
                    glowDish = if (focused) focus?.dish else null,
                    onPhoto = { list, i -> viewer = list to i },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    viewer?.let { (list, start) -> PhotoViewer(list, start, ZoneId.systemDefault(), onDismiss = { viewer = null }) }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Kitchen.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 13.sp, color = if (selected) Kitchen else MaterialTheme.colorScheme.onSurface, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

/**
 * One food murmur, journal style like the timeline: a quiet caption, the
 * words, the photos (tap to see them large), then the dishes. [glow] (0..1,
 * read while drawing) lights it up when it was opened onto, and [glowDish]
 * its chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoodNoteRow(
    n: FoodNote,
    photos: List<CameraPhoto>,
    glow: (() -> Float)? = null,
    glowDish: String? = null,
    onPhoto: (List<CameraPhoto>, Int) -> Unit,
) {
    val radius = with(LocalDensity.current) { 14.dp.toPx() }
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
            // Behind the row and a little wider than the words, so nothing moves.
            .drawBehind {
                val g = glow?.invoke() ?: 0f
                if (g > 0f) {
                    val pad = 10.dp.toPx()
                    drawRoundRect(Kitchen.copy(alpha = 0.18f * g), Offset(-pad, 0f), Size(size.width + 2 * pad, size.height), CornerRadius(radius))
                }
            }
            .padding(top = 6.dp, bottom = 10.dp),
    ) {
        MetaText(dayLabel(n.date) + "  ·  " + clockTime(n.atMs))
        Spacer(Modifier.height(6.dp))
        Text(n.body, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp))
        if (photos.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            PhotoStrip(photos, onPhoto)
        }
        if (n.dishes.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((name, made) in n.dishes) {
                    val base = if (made) Kitchen.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh
                    val lit = glow.takeIf { name == glowDish }
                    Text(
                        (if (made) "🍳 " else "") + name,
                        fontSize = 12.sp,
                        color = if (made) Kitchen else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .drawBehind {
                                drawRect(base)
                                lit?.invoke()?.takeIf { it > 0f }?.let { drawRect(Kitchen.copy(alpha = 0.22f * it)) }
                            }
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}
