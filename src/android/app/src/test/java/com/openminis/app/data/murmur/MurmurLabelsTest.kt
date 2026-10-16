package com.openminis.app.data.murmur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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


    @Test fun moodConfidenceIsTheTiersCalibratedShare() {
        // Calibration softens a lot (T = 2.4): bright at 0.8 raw is only ~0.64, at 0.99 ~0.87.
        val loose = MurmurLabels.MOODS.map { when (it) { "开心" -> 0.5; "满足" -> 0.3; "平静" -> 0.2; else -> 0.0 } }
        assertTrue(MurmurLabels.moodConfidence(loose, "开心")!! < MurmurLabels.MOOD_SHOW)
        val sure = MurmurLabels.MOODS.map { when (it) { "开心" -> 0.7; "满足" -> 0.29; "平静" -> 0.01; else -> 0.0 } }
        val c = MurmurLabels.moodConfidence(sure, "开心")!!
        assertTrue(c >= MurmurLabels.MOOD_SHOW && c < 0.9f)
        // Split across tiers: not shown.
        val torn = MurmurLabels.MOODS.map { when (it) { "开心" -> 0.5; "疲惫" -> 0.3; "平静" -> 0.2; else -> 0.0 } }
        assertTrue(MurmurLabels.moodConfidence(torn, "开心")!! < MurmurLabels.MOOD_SHOW)
        // Nothing read: unknown.
        assertNull(MurmurLabels.moodConfidence(emptyList(), "开心"))
    }

    @Test fun onlyASureMoodIsShown() {
        val probs = MurmurLabels.MOODS.map { if (it == "低落") 0.999 else 0.001 / 11 }
        val t = MurmurLabels.parse("记录|低落|工作|无|否", "今天好累", 0.1f, probs)!!
        assertEquals("低落", t.shownMood)
        assertNull(t.copy(moodConf = 0.6f).shownMood)
        assertNull(t.copy(moodConf = null).shownMood)
    }

    @Test fun curiosityWishesAndErrandsGetASecondLook() {
        val plain = MurmurLabels.parse("记录|平静|想法|无|否", "x", 0.001f)!!
        assertFalse(MurmurLabels.isCandidate("今天天气不错", plain))
        // A question left unasked.
        assertTrue(MurmurLabels.isCandidate("路边的银杏有一棵叶子半黄半绿", plain.copy(mood = "好奇")))
        // A wish.
        assertTrue(MurmurLabels.isCandidate("看完纪录片更想去动物园看看了", plain))
        // An errand.
        assertTrue(MurmurLabels.isCandidate("那个视频会员可以退订了", plain))
    }

    @Test fun aSuggestionRemembersItsChatAndBrief() {
        val s = MurmurSuggestion(
            "h", MurmurSuggestion.OFFER,
            title = "今晚提醒你吃药？", preview = "睡前半小时吃", sessionId = "sid1", promptVersion = MurmurAgent.BRIEF_VERSION,
        )
        val back = MurmurSuggestion.from(org.json.JSONObject(s.toJson().toString()))!!
        assertEquals("sid1", back.sessionId)
        assertEquals("睡前半小时吃", back.preview)
        assertEquals(MurmurAgent.BRIEF_VERSION, back.promptVersion)
        // Saved before versions (the old one-shot reminders): 0, no chat.
        val old = MurmurSuggestion.from(org.json.JSONObject("""{"h":"h","s":"none","t":"reminder","at":5,"c":1}"""))!!
        assertEquals(0, old.promptVersion)
        assertEquals("", old.sessionId)
    }

    @Test fun theSummaryIsMinissFirstLinePlain() {
        assertEquals("今晚 22:30 提醒你吃布洛芬？", MurmurAgent.headline("\n## **今晚 22:30 提醒你吃布洛芬？**\n\n细节……"))
        assertEquals("叶子半黄半绿多半是品种差异", MurmurAgent.headline("总结：叶子半黄半绿多半是品种差异\n\n……"))
        // An opener first: the marked line still wins.
        assertEquals("汤底多半是骨头熬了一夜", MurmurAgent.headline("查完了，我的判断如下。\n\n**摘要：汤底多半是骨头熬了一夜**\n\n细节……"))
        // The card's second line: what follows the summary.
        assertEquals("颜色发白，香味很浓", MurmurAgent.preview("查完了。\n\n摘要：多半是骨头汤\n\n- **颜色发白**，香味很浓\n- 其他"))
        assertEquals("", MurmurAgent.preview("摘要：只有一句"))
    }
}
