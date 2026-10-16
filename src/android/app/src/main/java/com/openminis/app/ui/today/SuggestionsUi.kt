package com.openminis.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * [T-today-murmur-agent] 智能建议 on a page of its own (SPEC §19.3): every chat
 * Minis prepared in the last days, under the murmur it came from, by day. The
 * timeline only shows today's few; this is where they all are.
 */
@Composable
internal fun SuggestionsPage(vm: TodayViewModel, onOpenChat: (String) -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    // Only the switch from Today's state: the page is not redrawn with every timeline change.
    val on by remember(vm) { vm.state.map { it.smartSuggest }.distinctUntilChanged() }.collectAsState(vm.state.value.smartSuggest)
    // Every suggestion written (made, opened, turned down, undone, cleared): read again.
    val changes by vm.murmurChanges.collectAsState()
    val working by vm.murmurWorking.collectAsState()
    val fresh by vm.murmurFresh.collectAsState()
    var items by remember { mutableStateOf<List<SuggestionItem>?>(null) }
    LaunchedEffect(changes) { items = vm.murmurSuggestionList() }
    val byDay = remember(items) { items?.groupBy { it.date } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val today = LocalDate.now()

    FullScreenPage(onDismiss, header = { PageTitle(stringResource(R.string.today_suggest_page_title), Modifier.weight(1f)) }) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                !on -> TodayEmpty("💬", stringResource(R.string.today_suggest_page_off)) {
                    TextButton(onClick = onSettings) { Text(stringResource(R.string.today_suggest_page_to_settings)) }
                }
                byDay == null -> Unit
                byDay.isEmpty() && working == null -> TodayEmpty("💬", stringResource(R.string.today_suggest_page_empty))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                    if (working != null) item(key = "working") { Working() }
                    for ((date, day) in byDay) {
                        item(key = "d:$date") {
                            Text(
                                dayHeading(date, today),
                                fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
                            )
                        }
                        items(day, key = { it.id }) { s ->
                            Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                                Row {
                                    Text(clockTime(s.atMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp, top = 1.dp))
                                    Text(s.body, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                                }
                                MurmurSuggestionLine(
                                    s.suggestion,
                                    onOpen = { scope.launch { vm.openMurmurChat(s.id, s.body)?.let { onDismiss(); onOpenChat(it) } } },
                                    onDismiss = {
                                        vm.dismissMurmurSuggestion(s.id, s.body)?.let { undo ->
                                            scope.offerUndo(snackbar, context.getString(R.string.today_suggest_dismissed), context.getString(R.string.today_undo), undo)
                                        }
                                    },
                                    fresh = s.id in fresh,
                                    onAppeared = { vm.murmurSeen(s.id) },
                                )
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Minis is preparing one right now. */
@Composable
private fun Working() {
    Row(
        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.today_suggest_page_working), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
    }
}
