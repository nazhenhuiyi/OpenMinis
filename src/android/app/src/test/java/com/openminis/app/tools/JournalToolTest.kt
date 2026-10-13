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
    fun editMovesTimeWithoutTouchingTheWords() = runBlocking {
        val s = store()
        val e = s.add("鱼鳞云", at = now.withHour(0).withMinute(40))
        val r = call(s, "action" to "edit", "id" to e.id!!, "time" to "08:36")
        assertTrue(r.output, r.success)
        val got = s.readDay(today)!!.entries.single()
        assertEquals("2026-10-11T08:36:00+08:00", got.attrs["at"])
        assertEquals("08:36", got.heading)
        assertEquals("鱼鳞云", got.body)
        assertFalse(got.writtenByMinis) // still the user's words
        assertTrue(got.attrs.containsKey("edited"))
    }

    @Test
    fun editMovesToAnotherDay() = runBlocking {
        val s = store()
        val e = s.add("昨晚的电影")
        val r = call(s, "action" to "edit", "id" to e.id!!, "date" to "2026-10-10", "time" to "21:10")
        assertTrue(r.output, r.success)
        assertTrue(s.readDay(today)!!.entries.isEmpty())
        val got = s.readDay(today.minusDays(1))!!.entries.single()
        assertEquals(e.id, got.id)
        assertEquals("2026-10-10T21:10:00+08:00", got.attrs["at"])
        assertFalse(call(s, "action" to "edit", "id" to e.id!!, "date" to "2026-10-12").success) // future
        assertFalse(call(s, "action" to "edit", "id" to e.id!!).success)                       // nothing to change
    }

    @Test
    fun photosAreImportedAndAttached() = runBlocking {
        val s = store()
        val imported = mutableListOf<String>()
        val importer: suspend (String) -> String? = { p ->
            if (p.startsWith("/var/minis/")) "DCIM/Minis/${p.substringAfterLast('/')}".also { imported += p } else null
        }
        fun run(vararg kv: Pair<String, String>) = runBlocking {
            val o = JSONObject().put("tool_title", "t")
            kv.forEach { (k, v) -> o.put(k, v) }
            JournalTool.execute(s, o.toString(), zone, importer)
        }
        val r = run("action" to "add", "content" to "云", "photos" to "/var/minis/attachments/uploads/a.jpg")
        assertTrue(r.output, r.success)
        val id = s.readDay(today)!!.entries.single().id!!
        assertEquals(listOf("DCIM/Minis/a.jpg"), s.readDay(today)!!.entries.single().photos)

        assertTrue(run("action" to "edit", "id" to id, "photos" to "/var/minis/workspace/b.png").success)
        assertEquals(listOf("DCIM/Minis/a.jpg", "DCIM/Minis/b.png"), s.readDay(today)!!.entries.single().photos)
        // Passing back a photo the entry already has changes nothing and says so.
        val again = run("action" to "edit", "id" to id, "photos" to "/var/minis/attachments/uploads/a.jpg")
        assertTrue(again.output, again.output.contains("already on this entry"))
        assertEquals(listOf("DCIM/Minis/a.jpg", "DCIM/Minis/b.png"), s.readDay(today)!!.entries.single().photos)

        // A path that is not there fails the call and writes nothing.
        assertFalse(run("action" to "add", "content" to "x", "photos" to "nope.jpg").success)
        assertEquals(1, s.readDay(today)!!.entries.size)
        // Bad arguments are caught before anything is imported.
        imported.clear()
        assertFalse(run("action" to "add", "content" to "x", "time" to "9pm", "photos" to "/var/minis/c.jpg").success)
        assertTrue(imported.isEmpty())
    }

    @Test
    fun everyResultSaysWhatTimeItIs() {
        val s = store()
        assertTrue(call(s, "action" to "list").output.endsWith("(Now: 2026-10-11 21:00 Asia/Shanghai)"))
        assertTrue(call(s, "action" to "rewrite").output.contains("(Now: 2026-10-11 21:00"))
    }

    @Test
    fun sameWordsAreNotAddedTwice() = runBlocking {
        val s = store()
        val first = call(s, "action" to "add", "content" to "鱼鳞云")
        val again = call(s, "action" to "add", "content" to "鱼鳞云", "time" to "08:36")
        assertTrue(again.output, again.success)
        assertTrue(again.output.startsWith("Not added"))
        assertEquals(1, s.readDay(today)!!.entries.size)
        // The user's own identical words do not block Minis.
        s.add("下雨了")
        assertTrue(call(s, "action" to "add", "content" to "下雨了").output.startsWith("Added"))
        assertTrue(first.success)
    }

    @Test
    fun backfillingAnotherDayNeedsATime() {
        val s = store()
        val r = call(s, "action" to "add", "content" to "看了电影", "date" to "2026-10-10")
        assertFalse(r.output, r.success)
        assertTrue(call(s, "action" to "add", "content" to "看了电影", "date" to "2026-10-10", "time" to "21:10").success)
    }

    @Test
    fun onlyEntriesAboutNowGetLocated() = runBlocking {
        val s = store()
        val located = mutableListOf<String>()
        fun run(vararg kv: Pair<String, String>) = runBlocking {
            val o = JSONObject().put("tool_title", "t")
            kv.forEach { (k, v) -> o.put(k, v) }
            JournalTool.execute(s, o.toString(), zone, onAddedNow = { _, id -> located += id })
        }
        run("action" to "add", "content" to "现在")
        run("action" to "add", "content" to "早上的事", "time" to "08:00")
        assertEquals(listOf(s.readDay(today)!!.entries.first { it.body == "现在" }.id), located)
    }

    @Test
    fun aCustomHeadingSurvivesAnEdit() = runBlocking {
        val s = store()
        val e = s.add("原文")
        val f = s.fileFor(today)
        f.writeText(f.readText().replace("## 21:00", "## 我自己的标题"))
        assertTrue(call(s, "action" to "edit", "id" to e.id!!, "content" to "改过的").success)
        assertTrue(f.readText().contains("## 我自己的标题\n"))
        // Moving it gives it the new time as its heading.
        assertTrue(call(s, "action" to "edit", "id" to e.id!!, "time" to "20:00").success)
        assertTrue(f.readText().contains("## 20:00\n"))
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

/** [T-today-habits] journal tool check-ins (SPEC §14.4). */
class JournalToolHabitsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = OffsetDateTime.of(2026, 10, 12, 20, 0, 0, 0, ZoneOffset.ofHours(8))
    private val today = now.toLocalDate()
    private fun store() = JournalStore(tmp.root, { zone }, { now })
    private fun call(s: JournalStore, vararg kv: Pair<String, Any>) = runBlocking {
        val o = JSONObject().put("tool_title", "t")
        kv.forEach { (k, v) -> o.put(k, v) }
        JournalTool.execute(s, o.toString(), zone)
    }

    @Test
    fun checkInByNameAccumulatesForToday() = runBlocking {
        val s = store()
        val h = s.addHabit("深蹲", 50, "个", null)
        assertTrue(call(s, "action" to "check_in", "habit" to "深蹲", "count" to 30).success)
        val r = call(s, "action" to "check_in", "habit" to h.id!!, "count" to 30)
        assertTrue(r.output, r.output.contains("60/50"))
        val e = s.readDay(today)!!.entries.single()
        assertEquals("60", e.attrs["done"])
        assertTrue(e.writtenByMinis)
        assertTrue(call(s, "action" to "habits").output.contains("today=60/50"))
    }

    @Test
    fun noCheckInWithoutCountCompletes() = runBlocking {
        val s = store()
        s.addHabit("冥想", null, null, null)
        assertTrue(call(s, "action" to "check_in", "habit" to "冥想").output.contains("done"))
        assertEquals(1, s.readDay(today)!!.entries.single().let(com.openminis.app.data.journal.CheckIns::done))
    }

    @Test
    fun pastDaysAndEditsAreRefused() = runBlocking {
        val s = store()
        s.addHabit("俯卧撑", 20, "个", null)
        val past = call(s, "action" to "check_in", "habit" to "俯卧撑", "date" to today.minusDays(1).toString())
        assertFalse(past.success)
        assertTrue(s.readDay(today.minusDays(1)) == null)
        call(s, "action" to "check_in", "habit" to "俯卧撑")
        val id = s.readDay(today)!!.entries.single().id!!
        assertFalse(call(s, "action" to "edit", "id" to id, "content" to "x").success)
    }
}
