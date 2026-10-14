package com.openminis.app.data.photos

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.InputStream
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
        val name = freshName(context, now, ext)
        val takenAt = runCatching { ExifInterface(src).dateTimeOriginal }.getOrNull() ?: src.lastModified()
        return try {
            copyIntoLibrary(context, src, name, mime, takenAt)?.let { "$DIR/$name" }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "import failed: ${e.message}")
            null
        }
    }

    /** Something picked for a note: its `photos=` path, and how to show it. */
    data class Picked(val relativePath: String, val uri: String, val isVideo: Boolean)

    /**
     * A photo or video from the system picker, as a `photos=` reference. The
     * picker hands out its own URIs, not the library's, so the original is
     * looked up — same name and size, else same bytes — and referenced
     * where it is: picking never duplicates a photo in the gallery. Only when
     * the original can't be seen (no or partial library access) is a copy
     * made in `DCIM/Minis/`, dated when the original was taken.
     */
    fun importPicked(context: Context, src: Uri, now: Date = Date()): Picked? = try {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        var takenAt = 0L
        resolver.query(src, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN).takeIf { it >= 0 }?.let { takenAt = c.getLong(it) }
            }
        }
        val mime = resolver.getType(src) ?: name?.let { n ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substringAfterLast('.', "").lowercase())
        }
        val video = mime?.startsWith("video/") == true
        if (mime == null || !(video || mime.startsWith("image/"))) {
            null
        } else {
            // The picker's own URI ends in the library row's _ID for items on this phone: try that before reading bytes.
            val found = if (size > 0) findInLibrary(context, video, size, name, src.lastPathSegment?.toLongOrNull()) { resolver.openInputStream(src) } else null
            AppLogger.info(TAG, "picked $mime size=$size: ${found?.relativePath ?: "not in the library, copying"}")
            found ?: run {
                val ext = (MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (video) "mp4" else "jpg")
                    .let { if (it == "jpeg") "jpg" else it }
                val file = freshName(context, now, ext)
                copyIntoLibrary(context, { resolver.openInputStream(src)!! }, file, mime, takenAt.takeIf { it > 0 } ?: now.time)
                    ?.let { Picked("$DIR/$file", it.toString(), video) }
            }
        }
    } catch (e: Exception) {
        AppLogger.warning(TAG, "pick import failed: ${e.message}")
        null
    }

    /**
     * The library row holding the same file as [open]: the row [id] (or one
     * named [name]) of that [size] is taken as the same file; any other row
     * of that size is compared byte for byte. Only rows this app may read are seen.
     */
    private fun findInLibrary(context: Context, video: Boolean, size: Long, name: String?, id: Long? = null, open: () -> InputStream?): Picked? {
        val resolver = context.contentResolver
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cols = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            @Suppress("DEPRECATION") add(MediaStore.MediaColumns.DATA)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
        }.toTypedArray()
        class Row(val uri: Uri, val id: Long, val name: String, val dir: String)
        val rows = ArrayList<Row>()
        resolver.query(collection, cols, "${MediaStore.MediaColumns.SIZE} = ?", arrayOf(size.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val rel = if (Build.VERSION.SDK_INT >= 29) c.getString(3) else null
                val dir = CameraRollRules.relativeDir(rel, c.getString(2))
                val n = c.getString(1) ?: continue
                val rowId = c.getLong(0)
                if (dir.isNotEmpty()) rows += Row(android.content.ContentUris.withAppendedId(collection, rowId), rowId, n, dir)
            }
        }
        fun picked(r: Row) = Picked(r.dir + r.name, r.uri.toString(), video)
        rows.firstOrNull { it.id == id || it.name == name }?.let { return picked(it) }
        for (r in rows) {
            val same = resolver.openInputStream(r.uri)?.use { a -> open()?.use { b -> sameBytes(a, b) } } ?: false
            if (same) return picked(r)
        }
        return null
    }

    /** A library photo with exactly [src]'s bytes, as a `photos=` path. */
    private fun findInLibrary(context: Context, src: File): String? = try {
        findInLibrary(context, video = false, size = src.length(), name = src.name) { src.inputStream() }?.relativePath
    } catch (e: Exception) {
        AppLogger.warning(TAG, "library lookup failed: ${e.message}")
        null
    }

    /** `MINIS_yyyyMMdd_HHmmss.ext`, with `_2`, `_3`… when that second's name is taken. */
    private fun freshName(context: Context, now: Date, ext: String): String {
        val base = "MINIS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        var name = "$base.$ext"
        var n = 2
        while (exists(context, name)) name = "${base}_${n++}.$ext"
        return name
    }

    private fun sameBytes(x: InputStream, y: InputStream): Boolean {
        val a = ByteArray(64 * 1024)
        val b = ByteArray(64 * 1024)
        while (true) {
            val n = fill(x, a)
            if (n != fill(y, b)) return false
            if (n == 0) return true
            for (i in 0 until n) if (a[i] != b[i]) return false
        }
    }

    /** Read until [buf] is full or the stream ends (read() may return short counts). */
    private fun fill(input: InputStream, buf: ByteArray): Int {
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
        copyIntoLibrary(context, { src.inputStream() }, name, mime, takenAt)

    private fun copyIntoLibrary(context: Context, open: () -> InputStream, name: String, mime: String, takenAt: Long): Uri? =
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val video = mime.startsWith("video/")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$DIR/")
                put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)!!.use { out -> open().use { it.copyTo(out) } }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
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
            dest.outputStream().use { out -> open().use { it.copyTo(out) } }
            dest.setLastModified(takenAt)
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
        // Files covers photos and videos: a name is taken whichever kind holds it.
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        return context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID), sel, arrayOf("$DIR/", name), null,
        )?.use { it.count > 0 } ?: false
    }
}
