package com.openminis.app.data.photos

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
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
    const val LIBRARY_DIR = DIR

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
            return copyIntoLibrary(context, staging, p.relativePath.substringAfterLast('/'), "image/jpeg")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "finish failed: ${e.message}")
            return null
        } finally {
            staging.delete()
        }
    }

    /**
     * The `photos=` path for an existing image file (a chat attachment the
     * agent journals). A photo picked from the library, or one "Ask Minis"
     * carried into the chat, is already there byte for byte: that original is
     * referenced, so the library and the timeline do not show it twice. Anything
     * else (a shot from the chat camera, a downloaded image) is copied into
     * `DCIM/Minis/`, dated by its EXIF capture time. Null if [src] is not an
     * image this can store.
     */
    fun importFile(context: Context, src: File, now: Date = Date()): String? {
        val ext = src.extension.lowercase().let { if (it == "jpeg") "jpg" else it }
        val mime = IMPORT_TYPES[ext] ?: return null
        if (!src.isFile || src.length() == 0L) return null
        findInLibrary(context, src)?.let { return it }
        val base = "MINIS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        var name = "$base.$ext"
        var n = 2
        while (exists(context, name)) name = "${base}_${n++}.$ext"
        val takenAt = runCatching { ExifInterface(src).dateTimeOriginal }.getOrNull() ?: src.lastModified()
        return try {
            copyIntoLibrary(context, src, name, mime, takenAt)?.let { "$DIR/$name" }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "import failed: ${e.message}")
            null
        }
    }

    /** A library photo with exactly [src]'s bytes, as a `photos=` path. Only rows this app may read are seen. */
    private fun findInLibrary(context: Context, src: File): String? = try {
        val resolver = context.contentResolver
        val cols = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            @Suppress("DEPRECATION") add(MediaStore.Images.Media.DATA)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Images.Media.RELATIVE_PATH)
        }.toTypedArray()
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
            "${MediaStore.Images.Media.SIZE} = ?", arrayOf(src.length().toString()), null,
        )?.use { c ->
            var found: String? = null
            while (found == null && c.moveToNext()) {
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                val same = resolver.openInputStream(uri)?.use { sameBytes(it, src) } ?: false
                if (!same) continue
                val rel = if (Build.VERSION.SDK_INT >= 29) c.getString(3) else null
                val dir = CameraRollRules.relativeDir(rel, c.getString(2))
                found = c.getString(1)?.takeIf { dir.isNotEmpty() }?.let { dir + it }
            }
            found
        }
    } catch (e: Exception) {
        AppLogger.warning(TAG, "library lookup failed: ${e.message}")
        null
    }

    private fun sameBytes(input: java.io.InputStream, file: File): Boolean {
        val a = ByteArray(64 * 1024)
        val b = ByteArray(64 * 1024)
        file.inputStream().use { f ->
            while (true) {
                val n = fill(input, a)
                if (n != fill(f, b)) return false
                if (n == 0) return true
                for (i in 0 until n) if (a[i] != b[i]) return false
            }
        }
    }

    /** Read until [buf] is full or the stream ends (read() may return short counts). */
    private fun fill(input: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private val IMPORT_TYPES = mapOf(
        "jpg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
        "heic" to "image/heic", "heif" to "image/heif", "gif" to "image/gif",
    )

    private fun copyIntoLibrary(context: Context, src: File, name: String, mime: String, takenAt: Long = src.lastModified()): Uri? =
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "$DIR/")
                put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    // Never leave a half-written row behind.
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Minis").apply { mkdirs() }
            val dest = File(dir, name)
            src.copyTo(dest, overwrite = false)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
            Uri.fromFile(dest)
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
