package com.openminis.app.data.photos

import java.time.Instant
import java.time.ZoneId

/**
 * [T-today-tab] One row of the system photo library, reduced to what the
 * Today timeline needs. [relativeDir] is normalised to the Android 10+
 * `RELATIVE_PATH` form (`DCIM/Camera/`) on every API level.
 */
data class CameraPhoto(
    val id: Long,
    val uri: String,
    val displayName: String,
    val relativeDir: String,
    val bucketId: String?,
    val bucketName: String?,
    val takenAtMs: Long,
    val width: Int,
    val height: Int,
    /** A video from the library: shown and played, never looked at by species ID. */
    val isVideo: Boolean = false,
    val durationMs: Long = 0,
) {
    /** Stable reference stored in journal `photos=` (SPEC §5.3): survives reinstall, unlike `_ID`. */
    val relativePath: String get() = relativeDir + displayName
}

/** Photos only: what species ID and the chat can look at (videos are shown, never looked at). */
val List<CameraPhoto>.stills: List<CameraPhoto> get() = filter { !it.isVideo }

enum class PhotoSource { MINIS, CAMERA, INCLUDED_ALBUM }

/** Pure rules from SPEC §5.1 / §5.4, kept free of Android types so they are unit-testable. */
object CameraRollRules {
    const val MINIS_DIR = "DCIM/Minis/"
    private const val CAMERA_DIR = "dcim/camera/"
    private val STORAGE_PREFIX = Regex("^/(?:storage/(?:emulated/\\d+|self/primary|[^/]+)|sdcard|mnt/sdcard)/")

    /**
     * `RELATIVE_PATH` when the platform has it (API 29+), else derived from the
     * absolute `DATA` path (API 26–28). Always ends with `/`.
     */
    fun relativeDir(relativePath: String?, dataPath: String?): String {
        relativePath?.takeIf { it.isNotBlank() }?.let { return if (it.endsWith("/")) it else "$it/" }
        val data = dataPath ?: return ""
        val stripped = STORAGE_PREFIX.replace(data, "")
        val dir = stripped.substringBeforeLast('/', missingDelimiterValue = "")
        return if (dir.isEmpty()) "" else "$dir/"
    }

    /** null = not a photo the user took; it never reaches the timeline. */
    fun classify(relativeDir: String, bucketId: String?, includedBuckets: Set<String>): PhotoSource? = when {
        relativeDir.startsWith(MINIS_DIR) -> PhotoSource.MINIS
        relativeDir.lowercase().startsWith(CAMERA_DIR) -> PhotoSource.CAMERA
        bucketId != null && bucketId in includedBuckets -> PhotoSource.INCLUDED_ALBUM
        else -> null
    }

    /** How close in time a library photo must be to a note to leave its group for that note (§18.2). */
    const val ABSORB_WINDOW_MS = 3 * 60 * 60 * 1000L

    /**
     * Does a photo attached to a note leave its automatic group (§18.2)? Yes
     * when Minis made it (a composer shot, a copy for a note) or it was taken
     * close to the note; an older photo picked for a note shows in both places.
     */
    fun absorbedBy(noteAtMs: Long, photo: CameraPhoto): Boolean =
        photo.relativeDir.startsWith(MINIS_DIR) || kotlin.math.abs(photo.takenAtMs - noteAtMs) <= ABSORB_WINDOW_MS

    /**
     * A video's location tag (ISO 6709, as `MediaMetadataRetriever` gives it):
     * `+31.2304+121.4737/`, maybe with an altitude after the longitude.
     * Null when absent, malformed, or the 0,0 some cameras write for "unknown".
     */
    fun parseIso6709(s: String?): Pair<Double, Double>? {
        val m = ISO6709.find(s ?: return null) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lng = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || (lat == 0.0 && lng == 0.0)) return null
        return lat to lng
    }

    private val ISO6709 = Regex("^([+-]\\d{1,2}(?:\\.\\d+)?)([+-]\\d{1,3}(?:\\.\\d+)?)")

    /**
     * Some ROM cameras ignore EXTRA_OUTPUT or also save their own copy, so a
     * shot taken from Today can show up twice. A camera-folder photo within
     * [windowMs] of a Minis photo with the same dimensions is the duplicate.
     */
    fun dedupe(photos: List<CameraPhoto>, windowMs: Long = 3_000): List<CameraPhoto> {
        val minis = photos.filter { it.relativeDir.startsWith(MINIS_DIR) }
        if (minis.isEmpty()) return photos
        return photos.filter { p ->
            if (p.relativeDir.startsWith(MINIS_DIR)) return@filter true
            minis.none { m ->
                m.isVideo == p.isVideo &&
                    kotlin.math.abs(m.takenAtMs - p.takenAtMs) <= windowMs &&
                    ((m.width == p.width && m.height == p.height) || (m.width == p.height && m.height == p.width))
            }
        }
    }
}

/** What the Today list renders, oldest first. [N] is the note type (kept generic for tests). */
sealed interface TimelineItem<out N> {
    val atMs: Long

    data class Note<N>(val note: N, override val atMs: Long) : TimelineItem<N>
    data class PhotoGroup(val photos: List<CameraPhoto>) : TimelineItem<Nothing> {
        override val atMs: Long get() = photos.first().takenAtMs
    }
}

/**
 * SPEC §5.2. A photo starts a new group when ANY of these hold, else it joins
 * the previous photo's group:
 *   1. more than [gapMs] after the previous photo
 *   2. a note was written between them
 *   3. the group would span more than [maxSpanMs]
 *   4. it is on a different local day
 */
object PhotoGrouper {
    const val DEFAULT_GAP_MS = 60 * 60 * 1000L
    const val DEFAULT_MAX_SPAN_MS = 3 * 60 * 60 * 1000L

    fun <N> build(
        notes: List<N>,
        noteTimeMs: (N) -> Long,
        photos: List<CameraPhoto>,
        zone: ZoneId,
        gapMs: Long = DEFAULT_GAP_MS,
        maxSpanMs: Long = DEFAULT_MAX_SPAN_MS,
    ): List<TimelineItem<N>> {
        // Notes sort before photos taken at the same instant.
        val events = notes.map { 0 to TimelineItem.Note(it, noteTimeMs(it)) as TimelineItem<N> } +
            photos.map { 1 to TimelineItem.PhotoGroup(listOf(it)) }
        val sorted = events.sortedWith(compareBy({ it.second.atMs }, { it.first })).map { it.second }

        val out = ArrayList<TimelineItem<N>>()
        var current: MutableList<CameraPhoto>? = null
        fun flush() {
            current?.let { out += TimelineItem.PhotoGroup(it.toList()) }
            current = null
        }
        for (item in sorted) {
            when (item) {
                is TimelineItem.Note -> { flush(); out += item }
                is TimelineItem.PhotoGroup -> {
                    val p = item.photos.single()
                    val g = current
                    val joins = g != null &&
                        p.takenAtMs - g.last().takenAtMs <= gapMs &&
                        p.takenAtMs - g.first().takenAtMs <= maxSpanMs &&
                        sameDay(p.takenAtMs, g.first().takenAtMs, zone)
                    if (joins) g!!.add(p) else { flush(); current = mutableListOf(p) }
                }
            }
        }
        flush()
        return out
    }

    private fun sameDay(a: Long, b: Long, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}
