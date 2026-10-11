package com.openminis.app.data.journal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** [T-today-tab] File-level behaviour of the journal store (SPEC §4.1, §4.5). */
class JournalStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Shanghai")
    private var now = OffsetDateTime.of(2026, 10, 11, 9, 32, 15, 0, ZoneOffset.ofHours(8))
    private fun store() = JournalStore(tmp.root, { zone }, { now })
    private val day = LocalDate.of(2026, 10, 11)

    @Test
    fun addCreatesDayFileWithFrontMatter() = runBlocking {
        val s = store()
        val e = s.add("今天的咖啡有点酸。")
        val f = s.fileFor(day)
        assertEquals("2026/10/2026-10-11.md", f.relativeTo(tmp.root).path)
        val text = f.readText()
        assertTrue(text.startsWith("---\ndate: 2026-10-11\n---\n\n## 09:32\n<!-- minis:entry id=${e.id} at=2026-10-11T09:32:15+08:00 -->\n"))
        assertTrue(text.endsWith("今天的咖啡有点酸。\n"))
        assertTrue(Ulid.isValid(e.id!!))
    }

    @Test
    fun dateFollowsLocalZoneNotUtc() = runBlocking {
        // 00:30 in Shanghai is still the previous day in UTC; the file must be the local day.
        now = OffsetDateTime.of(2026, 10, 12, 0, 30, 0, 0, ZoneOffset.ofHours(8))
        store().add("过了零点")
        assertTrue(store().fileFor(LocalDate.of(2026, 10, 12)).isFile)
    }

    @Test
    fun editDeleteAndAttrsTouchOnlyTheirEntry() = runBlocking {
        val s = store()
        val a = s.add("第一条")
        now = now.plusHours(1)
        val b = s.add("第二条")
        val before = s.fileFor(day).readText()

        now = now.plusMinutes(5)
        assertTrue(s.editBody(day, a.id!!, "第一条（改）"))
        val afterEdit = s.fileFor(day).readText()
        val bBlock = before.substring(before.indexOf("## 10:32"))
        assertTrue("second entry bytes unchanged", afterEdit.endsWith(bBlock))
        val edited = s.readDay(day)!!.entries.first()
        assertEquals("第一条（改）", edited.body)
        assertEquals("2026-10-11T10:37:15+08:00", edited.attrs["edited"])

        assertTrue(s.setAttrs(day, b.id!!, mapOf("lat" to "31.22345", "lng" to "121.44567", "place" to "静安寺附近")))
        assertEquals("静安寺附近", s.readDay(day)!!.entries[1].place)

        assertTrue(s.delete(day, a.id!!))
        assertEquals(listOf(b.id), s.readDay(day)!!.entries.map { it.id })
        assertFalse("deleting twice fails", s.delete(day, a.id!!))
    }

    @Test
    fun externalEditIsNotLostOnNextWrite() = runBlocking {
        val s = store()
        val a = s.add("app 写的")
        // Someone appends in Obsidian between our writes.
        val f = s.fileFor(day)
        f.writeText(f.readText() + "\nObsidian 里加的一行\n")
        assertTrue(s.setAttrs(day, a.id!!, mapOf("place" to "家")))
        assertTrue(f.readText().contains("Obsidian 里加的一行"))
    }

    @Test
    fun availableDaysNewestFirst() = runBlocking {
        val s = store()
        s.add("a")
        now = now.minusDays(3)
        s.add("b")
        assertEquals(listOf(day, day.minusDays(3)), s.availableDays())
    }

    @Test
    fun changesSignalBumpsOnWrite() = runBlocking {
        val s = store()
        val v0 = s.changes.value
        s.add("x")
        assertTrue(s.changes.value > v0)
    }
}

/** [T-today-tab] The agent's file tools refuse these paths (SPEC §4.1, §4.6). */
class JournalPathGuardTest {
    @Test
    fun journalPathsAreRecognised() {
        listOf(
            "/var/minis/journal",
            "/var/minis/journal/",
            "/var/minis/journal/2026/10/2026-10-11.md",
            "/var/minis/journal/../journal/x.md",
            "/var/minis/./journal/x.md",
            "/var/minis/workspace/../journal/2026/10/2026-10-11.md",
        ).forEach { assertTrue(it, JournalStore.isJournalLinuxPath(it)) }
        listOf(
            "/var/minis/journals/x.md",
            "/var/minis/journal-backup/x.md",
            "/var/minis/journal/../memory/x.md",
            "/var/minis/memory/2026-10-11.md",
            "/var/minis/workspace/journal/x.md",
        ).forEach { assertFalse(it, JournalStore.isJournalLinuxPath(it)) }
    }
}
