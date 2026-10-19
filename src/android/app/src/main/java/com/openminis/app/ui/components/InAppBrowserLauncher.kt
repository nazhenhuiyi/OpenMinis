package com.openminis.app.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri

/**
 * A composition-wide callback for opening a URL inside the app's WebView sheet.
 *
 * - For http/https links, callers should invoke this to trigger [UrlPreviewSheet]
 *   and keep the user in-app (iOS-style preview).
 * - For other schemes (mailto:, tel:, geo:, minis://, etc.) fall back to
 *   [openExternalUrl] which dispatches a normal system Intent.
 *
 * The root [InAppBrowserHost] provides this and renders the sheet when invoked.
 * If a screen reads the ambient outside a host (e.g. during tests), the default
 * implementation falls back to the external Intent so nothing silently no-ops.
 */
val LocalInAppBrowserLauncher = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * [T-today-wiki-link] The URL opener to call from a screen: the host's when one
 * is above, else the system browser. The ambient's old default was a silent
 * no-op (no screen sits under an [InAppBrowserHost]), so the species page's
 * Wikipedia link was a dead tap; a missing host now still opens the link.
 */
@Composable
fun rememberUrlOpener(): (String) -> Unit {
    val host = LocalInAppBrowserLauncher.current
    val context = LocalContext.current
    return remember(host, context) { host ?: { url: String -> openExternalUrl(context, url) } }
}

/**
 * Fire a system ACTION_VIEW intent for schemes we don't preview in-app
 * (mailto, tel, geo, sms, etc.) or when we explicitly want the external handler.
 */
fun openExternalUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/**
 * Host composable: provides [LocalInAppBrowserLauncher] and renders
 * [UrlPreviewSheet] when a URL is requested. Place this once near the top of
 * the app (around the NavHost) so any screen can open links in-app.
 *
 * Only http/https URLs are routed through the in-app sheet. Anything else
 * is delegated to a normal system Intent so mailto:/tel:/maps:/minis:// keep
 * working.
 */
@Composable
fun InAppBrowserHost(
    context: Context,
    content: @Composable () -> Unit,
) {
    var previewUrl by remember { mutableStateOf<String?>(null) }

    val launcher = remember(context) {
        { url: String ->
            val lower = url.trim().lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                previewUrl = url
            } else {
                openExternalUrl(context, url)
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalInAppBrowserLauncher provides launcher,
    ) {
        content()
    }

    previewUrl?.let { url ->
        UrlPreviewSheet(
            url = url,
            onDismiss = { previewUrl = null },
        )
    }
}
