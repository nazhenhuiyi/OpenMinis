package com.openminis.app.ui.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.MoodBad
import androidx.compose.material.icons.outlined.SentimentDissatisfied
import androidx.compose.material.icons.outlined.SentimentNeutral
import androidx.compose.material.icons.outlined.SentimentSatisfiedAlt
import androidx.compose.material.icons.outlined.SentimentVeryDissatisfied
import androidx.compose.material.icons.outlined.SentimentVerySatisfied
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.murmur.MoodTier
import com.openminis.app.data.murmur.MurmurSuggestion
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

// [T-today-murmur] Mood in the timeline and the month (SPEC §19.2), and
// suggestions under murmurs (§19.3).

/**
 * Translucent inks, so one palette works on the light and the dark page:
 * sage green, grey, mist blue. Never red — it reads as an alarm (§19.2).
 */
internal fun MoodTier.ink(): Color = when (this) {
    MoodTier.BRIGHT -> Color(0xFF84A98C)
    MoodTier.PLAIN -> Color(0xFF959BA4)
    MoodTier.DIM -> Color(0xFF7896BA)
}

internal fun MoodTier.soft(alpha: Float = 0.22f): Color = ink().copy(alpha = alpha)

@Composable
internal fun MoodTier.label(): String = stringResource(
    when (this) {
        MoodTier.BRIGHT -> R.string.today_mood_bright
        MoodTier.PLAIN -> R.string.today_mood_plain
        MoodTier.DIM -> R.string.today_mood_dim
    },
)

/** A neutral murmur says nothing: only words that carry a feeling are shown in the timeline. */
internal fun shownMood(word: String?): String? = word?.takeIf { it != "平静" && MoodTier.fromLabel(it) != MoodTier.PLAIN }

/**
 * Each mood word's two faces: a quiet line icon (the timeline) and an emoji
 * (the mood page). One table, so a word is never in one and missing from the
 * other; a word not in it (an older tier label) gets its tier's.
 */
private val MOOD_FACES: Map<String, Pair<ImageVector, String>> = mapOf(
    "开心" to (Icons.Outlined.SentimentVerySatisfied to "😄"),
    "满足" to (Icons.Outlined.SentimentSatisfiedAlt to "😊"),
    "感动" to (Icons.Outlined.FavoriteBorder to "🥹"),
    "期待" to (Icons.Outlined.AutoAwesome to "🤩"),
    "平静" to (Icons.Outlined.SentimentNeutral to "😌"),
    "好奇" to (Icons.Outlined.Explore to "🤔"),
    "疲惫" to (Icons.Outlined.Bedtime to "😪"),
    "低落" to (Icons.Outlined.SentimentDissatisfied to "😔"),
    "焦虑" to (Icons.Outlined.SentimentVeryDissatisfied to "😰"),
    "烦躁" to (Icons.Outlined.MoodBad to "😤"),
    "生气" to (Icons.Outlined.Whatshot to "😠"),
    "难受" to (Icons.Outlined.Healing to "😣"),
)

private fun moodFace(word: String): Pair<ImageVector, String> = MOOD_FACES[word] ?: when (MoodTier.of(word)) {
    MoodTier.BRIGHT -> Icons.Outlined.SentimentSatisfiedAlt to "😊"
    MoodTier.DIM -> Icons.Outlined.SentimentDissatisfied to "😔"
    MoodTier.PLAIN -> Icons.Outlined.SentimentNeutral to "😌"
}

/** One line icon per mood word; the tier gives its colour. */
internal fun moodIcon(word: String): ImageVector = moodFace(word).first

/** A mood word as an emoji, for the mood page (the timeline keeps [moodIcon]'s quiet line icons). */
internal fun moodEmoji(word: String): String = moodFace(word).second

/** A day's face: its most frequent mood word within the day's tier (the latest on a tie). */
internal fun MoodDay.word(): String? {
    val t = tier ?: return null
    return notes.filter { it.mood != null && it.tier == t }
        .sortedBy { it.atMs }
        .groupingBy { it.mood!! }.eachCount()
        .entries.maxByOrNull { it.value }?.key
}


/**
 * A murmur's mood after its time — "09:30 · ☺" — a small line icon in its
 * tier's ink, as quiet as the caption. Tap it to change (§19.2).
 */
@Composable
internal fun MoodWord(word: String, onClick: () -> Unit) {
    Box(Modifier.size(22.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(moodIcon(word), contentDescription = word, tint = MoodTier.of(word).ink(), modifier = Modifier.size(15.dp))
    }
}

/**
 * Change a murmur's mood: the words in three rows — bright, plain, low —
 * each in its tier's tint, the current one filled. Opens in place, no dialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoodPicker(current: String?, emoji: Boolean = false, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        for (t in MoodTier.entries) {
            FlowRow(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (w in MoodTier.words(t)) {
                    val on = w == current
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .background(t.soft(if (on) 0.45f else 0.14f))
                            .clickable { onPick(w) }
                            .padding(start = 9.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (emoji) Text(moodEmoji(w), fontSize = 15.sp)
                        else Icon(moodIcon(w), contentDescription = null, tint = if (on) MaterialTheme.colorScheme.onSurface else t.ink(), modifier = Modifier.size(15.dp))
                        Text(
                            w,
                            fontSize = 13.sp,
                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                            color = if (on) MaterialTheme.colorScheme.onSurface else t.ink(),
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The mood month (§19.2): each day that has a mood shows its face (emoji)
 * on the tier's soft tint, the date small beneath; plain numbers otherwise. Tap a day for its murmurs; tap a murmur's word
 * to change it — the user's word is kept in the journal and beats the model's.
 */
@Composable
internal fun MoodPage(vm: TodayViewModel, start: LocalDate, onDismiss: () -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(start)) }
    var days by remember { mutableStateOf<List<MoodDay>?>(null) }
    var selected by remember { mutableStateOf<LocalDate?>(start) }
    var picking by remember { mutableStateOf<String?>(null) }
    var tagging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    LaunchedEffect(month) {
        days = vm.moodMonth(month)
        // Murmurs older than the timeline window get their colours while the page is open.
        tagging = true
        vm.tagMoodBacklog { scope.launch { days = vm.moodMonth(month) } }
        days = vm.moodMonth(month)
        tagging = false
    }
    FullScreenPage(
        onDismiss,
        header = {
            PageTitle(stringResource(R.string.today_mood_title), Modifier.weight(1f))
            IconButton(onClick = { month = month.minusMonths(1); selected = null }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                DateTimeFormatter.ofPattern(stringResource(R.string.today_mood_month_pattern)).format(month),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { month = month.plusMonths(1); selected = null }, enabled = month < YearMonth.from(today)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            MonthGrid(month, days.orEmpty(), today, selected, onSelect = { selected = it; picking = null })
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                for (t in MoodTier.entries) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(t.ink()))
                    Text(t.label(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 5.dp, end = 14.dp))
                }
                if (tagging) {
                    Spacer(Modifier.weight(1f))
                    MetaText(stringResource(R.string.today_mood_tagging))
                }
            }
            selected?.let { d ->
                val day = days?.firstOrNull { it.date == d }
                Spacer(Modifier.height(28.dp))
                Text(dayLabel(d), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                if (day == null || day.notes.isEmpty()) {
                    MetaText(stringResource(R.string.today_mood_empty_day))
                } else {
                    for (n in day.notes) MoodNoteRow(n, picking = picking == n.id, onToggle = { picking = if (picking == n.id) null else n.id }) { word ->
                        picking = null
                        scope.launch {
                            vm.setNoteMoodNow(n.date, n.id, word)
                            days = vm.moodMonth(month)
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, days: List<MoodDay>, today: LocalDate, selected: LocalDate?, onSelect: (LocalDate) -> Unit) {
    val byDate = days.associateBy { it.date }
    val locale = Locale.getDefault()
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        for (dow in DayOfWeek.entries) {
            Text(
                dow.getDisplayName(TextStyle.NARROW, locale),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
    val lead = month.atDay(1).dayOfWeek.value - 1
    val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    for (week in cells.chunked(7)) {
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                val date = week.getOrNull(i)
                Column(Modifier.weight(1f).padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (date != null) {
                        val day = byDate[date]
                        val tier = day?.tier
                        val word = day?.word()
                        val future = date.isAfter(today)
                        // A day with a mood: its face on the tier's tint, the date small beneath.
                        // Without: just the date, where the face would be.
                        var m = Modifier.padding(horizontal = 3.dp).fillMaxWidth().aspectRatio(1f).clip(CircleShape)
                        if (tier != null) m = m.background(tier.soft(0.28f))
                        if (date == selected) m = m.border(1.5.dp, tier?.ink() ?: MaterialTheme.colorScheme.outline, CircleShape)
                        if (!future) m = m.clickable { onSelect(date) }
                        val dateColor = when {
                            future -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            tier != null -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        }
                        val bold = if (date == today) FontWeight.Bold else FontWeight.Normal
                        Box(m, contentAlignment = Alignment.Center) {
                            if (word != null) Text(moodEmoji(word), fontSize = 20.sp)
                            else Text(date.dayOfMonth.toString(), fontSize = 13.sp, fontWeight = bold, color = dateColor)
                        }
                        Text(
                            if (word != null) date.dayOfMonth.toString() else "",
                            fontSize = 10.sp,
                            fontWeight = bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodNoteRow(n: MoodNote, picking: Boolean, onToggle: () -> Unit, onPick: (String) -> Unit) {
    val time = remember(n.atMs) { clockTime(n.atMs) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetaText(time)
            Spacer(Modifier.width(8.dp))
            val tier = n.tier
            // Not coloured yet: "…"; it can still be set.
            Row(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(tier?.soft(0.16f) ?: Color.Transparent)
                    .clickable(onClick = onToggle)
                    .padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                n.mood?.let { Text(moodEmoji(it), fontSize = 13.sp) }
                Text(n.mood ?: "…", fontSize = 12.sp, color = tier?.ink() ?: MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            }
            if (n.byUser) {
                Spacer(Modifier.width(6.dp))
                MetaText(stringResource(R.string.today_mood_by_you))
            }
        }
        Text(n.body, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 4.dp))
        AnimatedVisibility(picking, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            MoodPicker(n.mood, emoji = true, onPick = onPick)
        }
    }
}

// ───────────────────────── Suggestions (§19.3) ─────────────────────────

/** Suggestion cards already shown once this run: they appear without the fade from then on. */
private val seenSuggestions = HashSet<String>()

/** Only a suggestion just made fades in, and only the first time it is seen. */
private const val FRESH_MS = 10 * 60 * 1000L

/**
 * A suggestion under a murmur (§19.3), in the same shape as the species
 * suggestion: a soft tinted card — the one line Minis wrote first, the start
 * of the rest beneath, "继续聊 ›" to carry on in that chat, and a faint ×
 * ("不用"). It fades in once, when it is new; scrolling past or coming back
 * to Today shows it as it is.
 */
@Composable
internal fun MurmurSuggestionLine(s: MurmurSuggestion, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val key = s.sessionId.ifEmpty { s.hash }
    val animate = remember(key) { key !in seenSuggestions && System.currentTimeMillis() - s.createdMs < FRESH_MS }
    var shown by remember(key) { mutableStateOf(!animate) }
    LaunchedEffect(key) { seenSuggestions += key; shown = true }
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val opened = s.state == MurmurSuggestion.OPENED
    AnimatedVisibility(shown, enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { -it / 3 }) {
        Row(
            Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = if (opened) 0.06f else 0.11f))
                .clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("💬", fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp, end = 10.dp))
            Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
                Text(s.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (opened) stringResource(R.string.today_suggest_opened_sub)
                    else s.preview.ifBlank { stringResource(R.string.today_suggest_minis_sub) },
                    fontSize = 12.sp, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp),
                )
            }
            Text(
                stringResource(R.string.today_suggest_continue) + " ›",
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            if (!opened) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.today_suggest_no), tint = sub.copy(alpha = 0.6f), modifier = Modifier.size(15.dp))
                }
            } else Spacer(Modifier.width(6.dp))
        }
    }
}
