package com.openminis.app.tools

import com.openminis.app.data.journal.JournalStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

/** [T-today-tab] The agent's journal tool (SPEC §4.6). */
class JournalToolTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = OffsetDateTime.of(2026, 10, 11, 21, 0, 0, 0, ZoneOffset.ofHours(8))
    private fun store() = JournalStore(tmp.root, { zone }, { now })
    private fun call(s: JournalStore, vararg kv: Pair<String, String>) = runBlocking {
        val o = JSONObject().put("tool_title", "t")
        kv.forEach { (k, v) -> o.put(k, v) }
        JournalTool.execute(s, o.toString(), zone)
    }
    private val today = LocalDate.of(2026, 10, 11)

    @Test
    fun addIsMarkedAsMinisAndBackfillsTime() = runBlocking {
        val s = store()
        val r = call(s, "action" to "add", "date" to "2026-10-10", "time" to "21:10", "content" to "和小林看了《好东西》。")
        assertTrue(r.output, r.success)
        val e = s.readDay(LocalDate.of(2026, 10, 10))!!.entries.single()
        assertEquals("2026-10-10T21:10:00+08:00", e.attrs["at"])
        assertTrue(e.writtenByMinis)
        assertEquals("和小林看了《好东西》。", e.body)
    }

    @Test
    fun listShowsIdsAndAuthorship() = runBlocking {
        val s = store()
        val mine = s.add("我自己写的")
        call(s, "action" to "add", "content" to "Minis 写的")
        val r = call(s, "action" to "list")
        assertTrue(r.output.contains("id=${mine.id} time=21:00"))
        assertTrue(r.output.contains("by=minis"))
        assertEquals(1, Regex("by=minis").findAll(r.output).count())
    }

    @Test
    fun editFindsEntryOnAnyDayAndMarksIt() = runBlocking {
        val s = store()
        val e = s.add("原文", at = now.minusDays(3))
        val r = call(s, "action" to "edit", "id" to e.id!!, "content" to "改过的")
        assertTrue(r.output, r.success)
        val got = s.readDay(today.minusDays(3))!!.entries.single()
        assertEquals("改过的", got.body)
        assertTrue(got.writtenByMinis)
        assertTrue(got.attrs.containsKey("edited"))
        // A later edit by the user in the app makes the words theirs again.
        s.editBody(today.minusDays(3), e.id!!, "我又改回来了")
        assertFalse(s.readDay(today.minusDays(3))!!.entries.single().writtenByMinis)
    }

    @Test
    fun deleteRemovesOnlyThatEntry() = runBlocking {
        val s = store()
        val a = s.add("留着")
        val b = s.add("删掉", at = now.minusMinutes(5))
        assertTrue(call(s, "action" to "delete", "id" to b.id!!).success)
        assertEquals(listOf(a.id), s.readDay(today)!!.entries.map { it.id })
    }

    @Test
    fun rejectsBadInput() {
        val s = store()
        assertFalse(call(s, "action" to "add").success)                                   // no content
        assertFalse(call(s, "action" to "add", "content" to "x", "date" to "10/11").success)
        assertFalse(call(s, "action" to "add", "content" to "x", "time" to "9pm").success)
        assertFalse(call(s, "action" to "add", "content" to "x", "date" to "2026-10-12").success) // future
        assertFalse(call(s, "action" to "edit", "id" to "NOPE", "content" to "x").success)
        assertFalse(call(s, "action" to "rewrite").success)
        assertTrue(call(s, "action" to "list", "date" to "2026-01-01").output.startsWith("No journal entries"))
    }
}
