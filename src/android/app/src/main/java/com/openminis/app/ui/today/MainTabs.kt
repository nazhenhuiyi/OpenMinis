package com.openminis.app.ui.today

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.share.PendingShare
import com.openminis.app.share.ShareCoordinator
import com.openminis.app.share.SharedShareStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * [T-today-tab] The top-level tabs (docs/design/today/SPEC.md §1): 今日,
 * chat, and 探索 (§20).
 *
 * Today and Explore are drawn as an overlay ABOVE the existing NavHost rather than as one
 * more destination in it: every launch path (deep links, share hand-off,
 * notifications, launch-session modes, crash breakers) is written against
 * SESSION_LIST being the bottom of the back stack, and keeping that intact
 * means none of them had to change. Any navigation the NavHost performs flips
 * the tab to [Tab.CHAT] (see AppNavigation), so those paths still land where
 * they always did.
 */
object MainTabs {
    enum class Tab { TODAY, CHAT, EXPLORE }

    private val _tab = MutableStateFlow(Tab.TODAY)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    /**
     * Whether the chat side is showing its session list (the tab's root), so
     * the bar belongs there too. Published by ChatSplitScaffold, which alone
     * knows whether its single pane shows the list or a chat.
     */
    val chatListVisible = MutableStateFlow(true)

    fun select(t: Tab) { _tab.value = t }

    /** [T-today-habit-reminder] `minis://today/habits` (the reminder's tap): Today opens its check-in page once loaded. */
    private val _openHabitsRequest = MutableStateFlow(false)
    val openHabitsRequest: StateFlow<Boolean> = _openHabitsRequest.asStateFlow()

    fun openHabits() {
        select(Tab.TODAY)
        _openHabitsRequest.value = true
    }

    fun consumeOpenHabits() { _openHabitsRequest.value = false }

    /** Height of [MainTabBar] above the gesture area; content consumes it as an inset. */
    val BAR_HEIGHT = 56.dp
}

/** Text-only tab bar: the selected tab is bold with a short underline, no icons. */
@Composable
fun MainTabBar(selected: MainTabs.Tab, onSelect: (MainTabs.Tab) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(MainTabs.BAR_HEIGHT).selectableGroup()) {
            TabLabel(stringResource(R.string.tab_today), selected == MainTabs.Tab.TODAY) { onSelect(MainTabs.Tab.TODAY) }
            TabLabel(stringResource(R.string.tab_chat), selected == MainTabs.Tab.CHAT) { onSelect(MainTabs.Tab.CHAT) }
            TabLabel(stringResource(R.string.tab_explore), selected == MainTabs.Tab.EXPLORE) { onSelect(MainTabs.Tab.EXPLORE) }
        }
    }
}

@Composable
private fun RowScope.TabLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxHeight().selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.size(width = 18.dp, height = 3.dp).clip(RoundedCornerShape(2.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}

/**
 * "Ask Minis" (SPEC §2.4): hand a note or photo group to a NEW chat as a
 * draft, through the same buffer the system share sheet uses, so ChatScreen
 * drops it into the composer and nothing is sent until the user does.
 */
object AskMinis {
    private const val TAG = "AskMinis"
    /** SPEC §12 Q4: cap what one tap can push into a prompt. */
    const val MAX_PHOTOS = 6

    suspend fun stage(context: Context, text: String, photoUris: List<String>) = withContext(Dispatchers.IO) {
        val dir = SharedShareStore.sharedFileDirectory(context)
        val items = mutableListOf(PendingShare.Item(PendingShare.Item.Kind.INLINE_TEXT, text))
        for (uri in photoUris.take(MAX_PHOTOS)) {
            val name = "today_${UUID.randomUUID()}.jpg"
            val ok = runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it) }
                } != null
            }.onFailure { AppLogger.warning(TAG, "copy failed: ${it.message}") }.getOrDefault(false)
            if (ok) items += PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, name)
        }
        SharedShareStore.savePendingShare(context, PendingShare(items, System.currentTimeMillis()))
    }

    /** Publish the staged share; call AFTER navigating to the new chat. */
    fun publish(context: Context) = ShareCoordinator.processPendingShare(context)
}
