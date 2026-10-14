package com.openminis.app.data.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * [T-today-tab] Reads the system photo library (MediaStore) for the Today
 * timeline: photos and videos alike. Which rows count is decided by [CameraRollRules]; this class only
 * does the I/O (docs/design/today/SPEC.md §5).
 */
class CameraRollSource(private val context: Context) {

    enum class Access { NONE, PARTIAL, FULL }

    /** Bucket (album) for the "included albums" setting. */
    data class Album(val bucketId: String, val name: String, val relativeDir: String, val count: Int)

    fun access(): Access {
        return when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> Access.FULL
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
            Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.FULL
            else -> Access.NONE
        }
    }

    /**
     * Photos are allowed but videos are not: a user who allowed photos before
     * videos were shown. Same permission group, so asking again is usually
     * granted without a dialog.
     */
    fun videosMissing(): Boolean =
        Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) && !granted(Manifest.permission.READ_MEDIA_VIDEO)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /**
     * Photos and videos the user took in `[startMs, endMs)`, oldest first,
     * already filtered (§5.1) and de-duplicated (§5.4). Photos Minis itself
     * wrote are readable without any permission (the app owns those rows).
     */
    suspend fun query(startMs: Long, endMs: Long, includedBuckets: Set<String>): List<CameraPhoto> =
        withContext(Dispatchers.IO) {
            val rows = queryRows(startMs, endMs)
            CameraRollRules.dedupe(
                rows.filter { CameraRollRules.classify(it.relativeDir, it.bucketId, includedBuckets) != null },
            )
        }

    /** Look up specific photos by relative path (journal `photos=`), whatever their date. */
    suspend fun findByRelativePaths(paths: Collection<String>): Map<String, CameraPhoto> =
        withContext(Dispatchers.IO) {
            if (paths.isEmpty()) return@withContext emptyMap()
            val names = paths.map { it.substringAfterLast('/') }.distinct()
            val placeholders = names.joinToString(",") { "?" }
            val rows = runQueries("${MediaStore.MediaColumns.DISPLAY_NAME} IN ($placeholders)", names.toTypedArray(), null)
            val wanted = paths.toSet()
            rows.filter { it.relativePath in wanted }.associateBy { it.relativePath }
        }

    /** Every album that has photos, for the include-albums picker. */
    suspend fun albums(): List<Album> = withContext(Dispatchers.IO) {
        runQueries(null, null, null)
            .groupBy { it.bucketId ?: it.relativeDir }
            .map { (id, ps) -> Album(id, ps.first().bucketName ?: ps.first().relativeDir, ps.first().relativeDir, ps.size) }
            .sortedByDescending { it.count }
    }

    /** Emits whenever the photo library changes, debounced by the caller. */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    private fun queryRows(startMs: Long, endMs: Long): List<CameraPhoto> {
        // DATE_TAKEN can be 0/null for some imports; fall back to DATE_ADDED (seconds).
        val taken = MediaStore.Images.Media.DATE_TAKEN
        val added = MediaStore.MediaColumns.DATE_ADDED
        val sel = "(($taken >= ? AND $taken < ?) OR (($taken IS NULL OR $taken = 0) AND $added >= ? AND $added < ?))"
        val args = arrayOf(startMs.toString(), endMs.toString(), (startMs / 1000).toString(), (endMs / 1000).toString())
        return runQueries(sel, args, "$taken ASC").sortedBy { it.takenAtMs }
    }

    /** Images, then videos: same columns, two collections (a video needs its own permission on 13+). */
    private fun runQueries(selection: String?, args: Array<String>?, order: String?): List<CameraPhoto> =
        runQuery(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, selection, args, order) +
            runQuery(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, selection, args, order)

    @Suppress("DEPRECATION")
    private fun runQuery(uri: Uri, video: Boolean, selection: String?, args: Array<String>?, order: String?): List<CameraPhoto> {
        // Images.Media and Video.Media share these column names.
        val cols = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.Images.Media.BUCKET_ID)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.DATA)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.IS_PENDING)
            if (video) add(MediaStore.Video.Media.DURATION)
        }.toTypedArray()
        val out = ArrayList<CameraPhoto>()
        try {
            context.contentResolver.query(uri, cols, selection, args, order)?.use { c ->
                val iId = c.getColumnIndexOrThrow(cols[0])
                val iName = c.getColumnIndexOrThrow(cols[1])
                val iTaken = c.getColumnIndexOrThrow(cols[2])
                val iAdded = c.getColumnIndexOrThrow(cols[3])
                val iW = c.getColumnIndexOrThrow(cols[4])
                val iH = c.getColumnIndexOrThrow(cols[5])
                val iBucket = c.getColumnIndexOrThrow(cols[6])
                val iBucketName = c.getColumnIndexOrThrow(cols[7])
                val iData = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                val iRel = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                val iPending = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING) else -1
                val iDuration = if (video) c.getColumnIndex(MediaStore.Video.Media.DURATION) else -1
                while (c.moveToNext()) {
                    if (iPending >= 0 && c.getInt(iPending) == 1) continue
                    val id = c.getLong(iId)
                    val taken = c.getLong(iTaken).takeIf { it > 0 } ?: (c.getLong(iAdded) * 1000)
                    out += CameraPhoto(
                        id = id,
                        uri = ContentUris.withAppendedId(uri, id).toString(),
                        displayName = c.getString(iName) ?: continue,
                        relativeDir = CameraRollRules.relativeDir(
                            if (iRel >= 0) c.getString(iRel) else null,
                            if (iData >= 0) c.getString(iData) else null,
                        ),
                        bucketId = c.getString(iBucket),
                        bucketName = c.getString(iBucketName),
                        takenAtMs = taken,
                        width = c.getInt(iW),
                        height = c.getInt(iH),
                        isVideo = video,
                        durationMs = if (iDuration >= 0) c.getLong(iDuration) else 0,
                    )
                }
            }
        } catch (e: SecurityException) {
            AppLogger.info(TAG, "${if (video) "video" else "photo"} query denied: ${e.message}")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "${if (video) "video" else "photo"} query failed: ${e.message}")
        }
        return out
    }

    private companion object {
        const val TAG = "CameraRoll"
    }
}
