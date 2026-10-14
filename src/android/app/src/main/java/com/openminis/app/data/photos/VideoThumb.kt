package com.openminis.app.data.photos

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.pxOrElse

/**
 * [T-today-tab] Coil model for a video's still: the system's own thumbnail
 * (cached by MediaStore, so cheap), or a frame read from the file before
 * Android 10. Keeps Coil free of a video decoder dependency.
 */
data class VideoThumb(val uri: Uri) {
    class Fetch(private val data: VideoThumb, private val options: Options) : Fetcher {
        override suspend fun fetch(): FetchResult {
            val w = options.size.width.pxOrElse { 512 }.coerceIn(96, 1024)
            val h = options.size.height.pxOrElse { 512 }.coerceIn(96, 1024)
            val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 29) {
                options.context.contentResolver.loadThumbnail(data.uri, Size(w, h), null)
            } else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(options.context, data.uri)
                    // Never a full-resolution frame for a thumbnail (a 4K one is ~33 MB).
                    val frame = if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
                    else r.frameAtTime?.let { f ->
                        val scale = minOf(1f, w.toFloat() / f.width, h.toFloat() / f.height)
                        if (scale >= 1f) f else Bitmap.createScaledBitmap(f, (f.width * scale).toInt(), (f.height * scale).toInt(), true).also { f.recycle() }
                    }
                    frame ?: throw java.io.IOException("no frame: ${data.uri}")
                } finally {
                    r.release()
                }
            }
            return DrawableResult(bitmap.toDrawable(options.context.resources), isSampled = true, dataSource = DataSource.DISK)
        }

        class Factory : Fetcher.Factory<VideoThumb> {
            override fun create(data: VideoThumb, options: Options, imageLoader: ImageLoader): Fetcher = Fetch(data, options)
        }
    }

    /** Memory-cache key; Coil skips the memory cache for models without one. */
    class Key : Keyer<VideoThumb> {
        override fun key(data: VideoThumb, options: Options): String = "video-thumb:" + data.uri
    }

    companion object {
        /** What to hand Coil for a library item: the image itself, or a video's still. */
        fun model(uri: String, isVideo: Boolean): Any = if (isVideo) VideoThumb(Uri.parse(uri)) else Uri.parse(uri)

        /** "0:23", "12:05", "1:02:03". */
        fun duration(ms: Long): String {
            val s = (ms + 500) / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
