package com.openminis.app.ui.today

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.openminis.app.R
import com.openminis.app.data.photos.CameraPhoto
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Pieces shared by Today's full-screen pages (心情, 我的菜单, 那一天).

/**
 * A card that is new fades in, once: [fresh] comes from the data (made in this
 * run, not yet seen), not from being composed — so scrolling past, switching
 * tabs or reloading shows it as it is. [onAppeared] once the fade is done, so
 * the data can stop calling it new.
 */
@Composable
internal fun AppearOnce(fresh: Boolean, onAppeared: () -> Unit, content: @Composable () -> Unit) {
    if (!fresh) {
        content()
        return
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        shown = true
        kotlinx.coroutines.delay(400)
        onAppeared()
    }
    AnimatedVisibility(shown, enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { -it / 3 }) { content() }
}

/** Something just done, said in a short snackbar that can take it back; a newer one replaces it. */
internal fun CoroutineScope.offerUndo(snackbar: SnackbarHostState, text: String, undoLabel: String, undo: () -> Unit) {
    launch {
        snackbar.currentSnackbarData?.dismiss()
        if (snackbar.showSnackbar(text, actionLabel = undoLabel, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) undo()
    }
}

/**
 * A page over everything, edge to edge: back arrow then [header] on top,
 * [content] below, status-bar icons matching the theme. Back closes it.
 */
@Composable
internal fun FullScreenPage(
    onDismiss: () -> Unit,
    header: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val lightBg = MaterialTheme.colorScheme.background.luminance() > 0.5f
        LaunchedEffect(lightBg) {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = lightBg
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                    header()
                }
                content()
            }
        }
    }
}

/** The usual page title, next to the back arrow. */
@Composable
internal fun PageTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = modifier)

/**
 * Photos as squares, three across. With [max], only that many show and the
 * last says how many more ("+4"); without, they all show, row after row.
 * Tapping one gives the whole list and its index.
 */
@Composable
internal fun PhotoStrip(
    photos: List<CameraPhoto>,
    onPhoto: (List<CameraPhoto>, Int) -> Unit,
    max: Int? = 3,
    corner: Dp = 10.dp,
    size: Int = 400,
) {
    val shown = if (max != null) photos.take(max) else photos
    Column {
        for ((r, row) in shown.chunked(3).withIndex()) {
            Row(Modifier.padding(top = if (r > 0) 6.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { k, p ->
                    val i = r * 3 + k
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(corner)).clickable { onPhoto(photos, i) }) {
                        PhotoImage(p, Modifier.fillMaxSize(), size = size)
                        if (i == shown.lastIndex && photos.size > shown.size) {
                            Box(Modifier.fillMaxSize().background(Color(0x73000000)), contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(R.string.today_more_photos, photos.size - shown.size),
                                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
                // Keep the squares the same size when a row is short.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
