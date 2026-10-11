package com.openminis.app.data.journal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * [T-today-tab] Runs the SHARED journal vectors (src/shared/journal, the same
 * file iOS will run) so both platforms read and write a day file identically.
 */
class JournalVectorsTest {

    private val vectors: JSONObject = run {
        val dir = sequenceOf(File("../shared/journal"), File("../../shared/journal"))
            .firstOrNull { it.isDirectory } ?: File("../../shared/journal")
        JSONObject(File(dir, "journal_test_vectors.json").readText())
    }

    /** `[[key, value], ...]` — order is part of the format, and JSON objects are unordered. */
    private fun JSONObject.attrs(name: String): LinkedHashMap<String, String> {
        val arr = getJSONArray(name)
        val out = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) out[arr.getJSONArray(i).getString(0)] = arr.getJSONArray(i).getString(1)
        return out
    }

    @Test
    fun parse() {
        val cases = vectors.getJSONArray("parse")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val doc = JournalCodec.parse(c.getString("input"))
            val exp = c.getJSONObject("expect")
            assertEquals("$name: head", exp.getString("head"), doc.head)
            val entries = exp.getJSONArray("entries")
            assertEquals("$name: entry count", entries.length(), doc.entries.size)
            for (j in 0 until entries.length()) {
                val e = entries.getJSONObject(j)
                val got = doc.entries[j]
                assertEquals("$name[$j]: heading", e.getString("heading"), got.heading)
                assertEquals("$name[$j]: body", e.getString("body"), got.body)
                val attrs = e.attrs("attrs")
                assertEquals("$name[$j]: attrs", attrs, got.attrs)
                assertEquals("$name[$j]: attr order", attrs.keys.toList(), got.attrs.keys.toList())
            }
            assertEquals("$name: stray markers", exp.optInt("stray", 0), doc.strayMarkerCount)
        }
    }

    @Test
    fun parsedBlocksCoverTheWholeFile() {
        // Head + every block, concatenated, must give back the exact input.
        val cases = vectors.getJSONArray("parse")
        for (i in 0 until cases.length()) {
            val input = cases.getJSONObject(i).getString("input")
            val doc = JournalCodec.parse(input)
            val rebuilt = doc.head + doc.blocks.joinToString("") { input.substring(it.start, it.end) }
            assertEquals(cases.getJSONObject(i).getString("name"), input, rebuilt)
        }
    }

    @Test
    fun render() {
        val cases = vectors.getJSONArray("render")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.getString("name"), c.getString("expect"), JournalCodec.renderAttrs(c.attrs("attrs")))
        }
    }

    @Test
    fun renderedAttrsParseBack() {
        val tricky = linkedMapOf(
            "id" to "X1",
            "place" to "A \"B\" & C -- D --> E",
            "empty" to "",
            "emoji" to "🐈 楼下",
            "dash" to "a-",
        )
        assertEquals(tricky, JournalCodec.parseAttrs(JournalCodec.renderAttrs(tricky)))
        // And it must stay a legal HTML comment: no "--" inside.
        val line = "<!-- minis:entry ${JournalCodec.renderAttrs(tricky)} -->"
        assertEquals(false, line.removePrefix("<!--").removeSuffix("-->").contains("--"))
    }

    @Test
    fun edit() {
        val cases = vectors.getJSONArray("edit")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val replacement = c.optJSONObject("entry")?.let {
                JournalEntry("", it.attrs("attrs"), it.getString("body"))
            }
            val got = JournalCodec.replace(c.getString("input"), c.getString("id"), if (c.getString("op") == "delete") null else replacement)
            if (c.isNull("expect")) assertNull(name, got) else assertEquals(name, c.getString("expect"), got)
        }
    }

    @Test
    fun merge() {
        val cases = vectors.getJSONArray("merge")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val local = if (c.isNull("local")) null else c.getString("local")
            val exp = c.getJSONObject("expect")
            when (val r = JournalMerge.merge(local, c.getString("incoming"))) {
                is JournalMerge.Result.Merged -> {
                    assertEquals("$name: result", exp.getString("result"), "merged")
                    assertEquals("$name: text", exp.getString("text"), r.text)
                    assertEquals("$name: added", exp.getInt("added"), r.added)
                    assertEquals("$name: updated", exp.getInt("updated"), r.updated)
                }
                JournalMerge.Result.Unchanged -> assertEquals(name, exp.getString("result"), "unchanged")
                JournalMerge.Result.Unmergeable -> assertEquals(name, exp.getString("result"), "unmergeable")
            }
        }
    }
}
