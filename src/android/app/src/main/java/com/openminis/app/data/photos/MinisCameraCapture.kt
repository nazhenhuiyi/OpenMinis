package com.openminis.app.data.photos

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.openminis.app.logging.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-today-tab] Photos taken from the Today composer end up in the system
 * library under `DCIM/Minis/` (docs/design/today/SPEC.md §5.3), so the
 * gallery, the user's cloud backup and the timeline all see the same file.
 *
 * The camera writes to a PRIVATE staging file (FileProvider, the same
 * `camera-photos/` root the chat camera uses) and Minis copies it into the
 * library afterwards. Handing the camera a MediaStore row directly does not
 * work: a row is only openable by its owner while IS_PENDING=1, so the stock
 * camera failed on "Done" (seen on the API 35 emulator), and dropping
 * IS_PENDING would expose a zero-byte photo in every gallery meanwhile.
 *
 * The camera's resultCode is not trusted, same as the chat camera (MIUI can
 * report CANCELED after writing the file): the staging file's size decides.
 */
object MinisCameraCapture {
    private const val TAG = "MinisCamera"
    private const val DIR = "DCIM/Minis"

    data class Pending(
        /** FileProvider URI handed to ACTION_IMAGE_CAPTURE as EXTRA_OUTPUT. */
        val captureUri: Uri,
        val stagingFile: String,
        /** `DCIM/Minis/MINIS_yyyyMMdd_HHmmss.jpg` — what journal `photos=` stores. */
        val relativePath: String,
    )

    fun prepare(context: Context, now: Date = Date()): Pending? = try {
        val base = "MINIS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        // Name collisions within a second: MediaStore would rename silently,
        // which would break the stored reference — pick a free name up front.
        var name = "$base.jpg"
        var n = 2
        while (exists(context, name)) name = "${base}_${n++}.jpg"
        val dir = File(context.filesDir, "camera-photos").apply { mkdirs() }
        val staging = File(dir, "today_$name")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staging)
        Pending(uri, staging.absolutePath, "$DIR/$name")
    } catch (e: Exception) {
        AppLogger.warning(TAG, "prepare failed: ${e.message}")
        null
    }

    /** Move the shot into the library. Returns its content URI, or null if nothing was taken. */
    fun finish(context: Context, p: Pending): Uri? {
        val staging = File(p.stagingFile)
        try {
            if (!staging.isFile || staging.length() == 0L) return null
            val name = p.relativePath.substringAfterLast('/')
            return if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "$DIR/")
                    put(MediaStore.Images.Media.DATE_TAKEN, staging.lastModified())
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = resolver.insert(collection, values) ?: return null
                try {
                    resolver.openOutputStream(uri)!!.use { out -> staging.inputStream().use { it.copyTo(out) } }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    uri
                } catch (e: Exception) {
                    // Never leave a half-written row behind.
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Minis").apply { mkdirs() }
                val dest = File(dir, name)
                staging.copyTo(dest, overwrite = false)
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("image/jpeg"), null)
                Uri.fromFile(dest)
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "finish failed: ${e.message}")
            return null
        } finally {
            staging.delete()
        }
    }

    /** Remove a just-taken photo the user discarded from the composer (SPEC §2.3). */
    fun discard(context: Context, uri: Uri) {
        runCatching {
            if (uri.scheme == "file") File(uri.path!!).delete() else context.contentResolver.delete(uri, null, null)
        }.onFailure { AppLogger.warning(TAG, "discard failed: ${it.message}") }
    }

    private fun exists(context: Context, name: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) {
            @Suppress("DEPRECATION")
            return File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Minis"), name).exists()
        }
        val sel = "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), sel, arrayOf("$DIR/", name), null,
        )?.use { it.count > 0 } ?: false
    }
}
