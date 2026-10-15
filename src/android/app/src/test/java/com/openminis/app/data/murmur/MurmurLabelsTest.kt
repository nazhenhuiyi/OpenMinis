package com.openminis.app.data.murmur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MurmurLabelsTest {
    @Test fun parsesATagLine() {
        val text = "今天 34 分钟游了800米，感觉还是很不错的"
        val t = MurmurLabels.parse("记录|满足|运动|数量:34分钟;数量:800米;运动:游泳|是", text, 0.01f)!!
        assertEquals("记录", t.intent)
        assertEquals(MoodTier.BRIGHT, t.tier)
        assertEquals(listOf("运动"), t.aspects)
        // "34分钟" matches "34 分钟" (spaces ignored); "游泳" is not in the text and is dropped.
        assertEquals(listOf("数量" to "34分钟", "数量" to "800米"), t.mentions)
        assertTrue(t.highlight)
    }

    @Test fun rejectsLinesOutsideTheFormat() {
        assertNull(MurmurLabels.parse("记录|满足|运动|无", "x", 0f))
        assertNull(MurmurLabels.parse("备忘|满足|运动|无|否", "x", 0f))
        assertNull(MurmurLabels.parse("记录|狂喜|运动|无|否", "x", 0f))
        assertNull(MurmurLabels.parse("记录|满足|天文|无|否", "x", 0f))
    }

    @Test fun candidateByIntentScoreOrKeyword() {
        val plain = MurmurLabels.parse("记录|平静|想法|无|否", "人生就是这样", 0.001f)!!
        assertFalse(MurmurLabels.isCandidate("人生就是这样", plain))
        assertTrue(MurmurLabels.isCandidate("人生就是这样", plain.copy(actScore = 0.05f)))
        assertTrue(MurmurLabels.isCandidate("这是什么花呀", plain))
        assertTrue(MurmurLabels.isCandidate("明天去银行", plain))
    }

    @Test fun dayVoteTiesArePlain() {
        assertNull(MoodTier.ofDay(emptyList()))
        assertEquals(MoodTier.BRIGHT, MoodTier.ofDay(listOf(MoodTier.BRIGHT, MoodTier.PLAIN)))
        assertEquals(MoodTier.PLAIN, MoodTier.ofDay(listOf(MoodTier.BRIGHT, MoodTier.DIM)))
        assertEquals(MoodTier.DIM, MoodTier.ofDay(listOf(MoodTier.DIM, MoodTier.DIM, MoodTier.BRIGHT)))
    }

    @Test fun grammarCoversEveryList() {
        for (x in MurmurLabels.MOODS + MurmurLabels.ASPECTS + MurmurLabels.MENTION_TYPES) assertTrue(x, "\"$x\"" in MurmurLabels.GRAMMAR)
    }

    private val now = ZonedDateTime.of(2026, 10, 15, 20, 0, 0, 0, ZoneId.of("Asia/Shanghai"))

    @Test fun cloudReminderMustBeInTheFuture() {
        val later = MurmurCloud.parse("""好的 {"type":"reminder","title":"吃布洛芬","at":"2026-10-15T22:30"}""", "h", now)!!
        assertEquals(MurmurSuggestion.OFFER, later.state)
        assertEquals("吃布洛芬", later.title)
        val past = MurmurCloud.parse("""{"type":"reminder","title":"吃药","at":"2026-10-15T08:00"}""", "h", now)!!
        assertEquals(MurmurSuggestion.NONE, past.state)
    }

    @Test fun cloudAnswerNoneAndGarbage() {
        val a = MurmurCloud.parse("""{"type":"answer","question":"这是什么花","answer":"可能是牵牛花"}""", "h", now)!!
        assertEquals(MurmurSuggestion.TYPE_ANSWER, a.type)
        assertEquals(MurmurSuggestion.NONE, MurmurCloud.parse("""{"type":"none"}""", "h", now)!!.state)
        assertNull(MurmurCloud.parse("不知道", "h", now))
    }
}
