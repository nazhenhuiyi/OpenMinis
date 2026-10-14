package com.openminis.app.data.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** [T-today-tab] SPEC §5.1 (which photos count), §5.2 (grouping), §5.4 (dedupe). */
class CameraRollRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun t(h: Int, m: Int, day: Int = 11) =
        LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant().toEpochMilli()

    private var nextId = 1L
    private fun photo(at: Long, dir: String = "DCIM/Camera/", w: Int = 4000, h: Int = 3000) =
        CameraPhoto(nextId++, "content://x/${nextId}", "IMG_$nextId.jpg", dir, "b", "Camera", at, w, h)

    // ── §5.1 ──

    @Test
    fun relativeDirFromEitherColumn() {
        assertEquals("DCIM/Camera/", CameraRollRules.relativeDir("DCIM/Camera/", null))
        assertEquals("DCIM/Camera/", CameraRollRules.relativeDir("DCIM/Camera", null))
        assertEquals("DCIM/Camera/", CameraRollRules.relativeDir(null, "/storage/emulated/0/DCIM/Camera/IMG_1.jpg"))
        assertEquals("Pictures/WeiXin/", CameraRollRules.relativeDir("", "/storage/emulated/0/Pictures/WeiXin/a.jpg"))
        assertEquals("DCIM/Camera/", CameraRollRules.relativeDir(null, "/sdcard/DCIM/Camera/IMG_1.jpg"))
        assertEquals("DCIM/Camera/", CameraRollRules.relativeDir(null, "/storage/1A2B-3C4D/DCIM/Camera/IMG_1.jpg"))
    }

    @Test
    fun onlyCameraAndMinisFoldersByDefault() {
        val none = emptySet<String>()
        assertEquals(PhotoSource.CAMERA, CameraRollRules.classify("DCIM/Camera/", "1", none))
        assertEquals(PhotoSource.CAMERA, CameraRollRules.classify("dcim/camera/", "1", none))
        assertEquals(PhotoSource.MINIS, CameraRollRules.classify("DCIM/Minis/", "2", none))
        assertNull(CameraRollRules.classify("Pictures/WeiXin/", "3", none))
        assertNull(CameraRollRules.classify("Pictures/Screenshots/", "4", none))
        assertNull(CameraRollRules.classify("DCIM/Screenshots/", "5", none))
        assertNull(CameraRollRules.classify("Download/", "6", none))
        // A camera that saves elsewhere can be opted in by album.
        assertEquals(PhotoSource.INCLUDED_ALBUM, CameraRollRules.classify("DCIM/100ANDRO/", "7", setOf("7")))
    }

    // ── §5.4 ──

    @Test
    fun dedupeDropsTheCameraCopyOfAMinisShot() {
        val minis = photo(t(16, 20), "DCIM/Minis/")
        val copy = photo(t(16, 20) + 1_500, "DCIM/Camera/")
        val rotated = photo(t(16, 20) + 2_000, "DCIM/Camera/", w = 3000, h = 4000)
        val other = photo(t(16, 20) + 2_000, "DCIM/Camera/", w = 1920, h = 1080)
        val later = photo(t(16, 21), "DCIM/Camera/")
        assertEquals(listOf(minis, other, later), CameraRollRules.dedupe(listOf(minis, copy, rotated, other, later)))
    }

    @Test
    fun aNoteTakesInItsOwnAndNearbyPhotosOnly() {
        val note = t(18, 0)
        assertTrue(CameraRollRules.absorbedBy(note, photo(t(17, 50))))
        assertTrue(CameraRollRules.absorbedBy(note, photo(t(15, 0))))
        // Picked from days ago: stays in its own day's group too.
        assertFalse(CameraRollRules.absorbedBy(note, photo(t(9, 0, day = 9))))
        // Minis' own file (a composer shot, a copy for a note) belongs to the note whatever its date.
        assertTrue(CameraRollRules.absorbedBy(note, photo(t(9, 0, day = 9), "DCIM/Minis/")))
    }

    @Test
    fun videoLocationTag() {
        assertEquals(31.2304 to 121.4737, CameraRollRules.parseIso6709("+31.2304+121.4737/"))
        assertEquals(-33.8688 to 151.2093, CameraRollRules.parseIso6709("-33.8688+151.2093+012.500/"))
        assertEquals(40.7128 to -74.006, CameraRollRules.parseIso6709("+40.7128-074.0060/"))
        assertNull(CameraRollRules.parseIso6709("+00.0000+000.0000/"))
        assertNull(CameraRollRules.parseIso6709(""))
        assertNull(CameraRollRules.parseIso6709(null))
    }

    @Test
    fun aVideoIsNeverTakenForTheCopyOfAPhoto() {
        val minis = photo(t(16, 20), "DCIM/Minis/")
        // Same moment, same size: still a video the user shot, not a duplicate of the photo.
        val video = photo(t(16, 20) + 1_000, "DCIM/Camera/").copy(isVideo = true, durationMs = 12_000)
        assertEquals(listOf(minis, video), CameraRollRules.dedupe(listOf(minis, video)))
    }

    @Test
    fun videosGroupWithPhotosLikeAnyShot() {
        val a = photo(t(9, 0))
        val v = photo(t(9, 20)).copy(isVideo = true)
        val b = photo(t(9, 40))
        val items = PhotoGrouper.build(emptyList<N>(), { it.at }, listOf(a, v, b), zone)
        assertEquals(listOf(listOf(a, v, b)), items.map { (it as TimelineItem.PhotoGroup).photos })
    }

    // ── §5.2 ──

    private data class N(val at: Long)

    private fun groups(items: List<TimelineItem<N>>) = items.map {
        when (it) {
            is TimelineItem.Note -> "note"
            is TimelineItem.PhotoGroup -> it.photos.size.toString()
        }
    }

    @Test
    fun gapOverAnHourStartsANewGroup() {
        val ps = listOf(photo(t(12, 10)), photo(t(12, 55)), photo(t(13, 55)), photo(t(14, 56)))
        // 12:10→12:55 (45m) joins; →13:55 (60m) joins (≤ gap); →14:56 (61m) splits.
        assertEquals(listOf("3", "1"), groups(PhotoGrouper.build(emptyList<N>(), { it.at }, ps, zone)))
    }

    @Test
    fun aNoteSplitsAGroup() {
        val ps = listOf(photo(t(12, 10)), photo(t(12, 20)), photo(t(12, 40)))
        val out = PhotoGrouper.build(listOf(N(t(12, 30))), { it.at }, ps, zone)
        assertEquals(listOf("2", "note", "1"), groups(out))
    }

    @Test
    fun spanIsCappedAtThreeHours() {
        // One shot every 50 minutes for 5 hours: never a 60-minute gap.
        val ps = (0..6).map { photo(t(9, 0) + it * 50 * 60_000L) }
        val out = PhotoGrouper.build(emptyList<N>(), { it.at }, ps, zone)
        // 09:00..11:30 (4 photos, 150m), 12:20 starts new: 12:20..14:00 (3 photos).
        assertEquals(listOf("4", "3"), groups(out))
    }

    @Test
    fun midnightSplitsEvenWithinAnHour() {
        val ps = listOf(photo(t(23, 40, day = 10)), photo(t(0, 10, day = 11)))
        assertEquals(listOf("1", "1"), groups(PhotoGrouper.build(emptyList<N>(), { it.at }, ps, zone)))
    }

    @Test
    fun outputIsChronologicalAndGroupTimeIsFirstPhoto() {
        val ps = listOf(photo(t(15, 0)), photo(t(8, 50)), photo(t(15, 20)))
        val out = PhotoGrouper.build(listOf(N(t(9, 32))), { it.at }, ps, zone)
        assertEquals(listOf(t(8, 50), t(9, 32), t(15, 0)), out.map { it.atMs })
        assertEquals(listOf("1", "note", "2"), groups(out))
    }

    @Test
    fun customGap() {
        val ps = listOf(photo(t(12, 0)), photo(t(12, 40)))
        assertEquals(listOf("1", "1"), groups(PhotoGrouper.build(emptyList<N>(), { it.at }, ps, zone, gapMs = 30 * 60_000L)))
    }
}
