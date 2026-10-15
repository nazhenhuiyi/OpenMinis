package com.openminis.app.data.murmur

/**
 * [T-today-murmur] The tag line the on-device model writes for one 碎碎念
 * (SPEC §19, docs/design/today/MURMUR_MODEL.md): `意图|情绪|方面|提及|高光`.
 * Mirrors scripts/murmur_train/schema.py — the model was trained on exactly
 * this format, so the lists must not change without retraining.
 */
data class MurmurTags(
    val intent: String,
    val mood: String,
    val aspects: List<String>,
    /** (type, words) — the words always appear in the text (see [MurmurLabels.parse]). */
    val mentions: List<Pair<String, String>>,
    val highlight: Boolean,
    /** 1 − P(first token is 记录): how likely the model thinks something needs doing. */
    val actScore: Float,
) {
    val tier: MoodTier get() = MoodTier.of(mood)
}

/** What the day dot and the month calendar show (§19.2). */
enum class MoodTier(val label: String) {
    BRIGHT("明亮"), PLAIN("平常"), DIM("低沉");

    fun next(): MoodTier = entries[(ordinal + 1) % entries.size]

    companion object {
        private val POSITIVE = setOf("开心", "满足", "感动", "期待")
        private val NEGATIVE = setOf("疲惫", "低落", "焦虑", "烦躁", "生气", "难受")

        /** A mood word's tier; also accepts a tier's own label (older user corrections). */
        fun of(mood: String): MoodTier = fromLabel(mood) ?: when (mood) {
            in POSITIVE -> BRIGHT
            in NEGATIVE -> DIM
            else -> PLAIN
        }

        /** The words of a tier, in MurmurLabels.MOODS order — what the picker offers. */
        fun words(t: MoodTier): List<String> = MurmurLabels.MOODS.filter { of(it) == t }

        fun fromLabel(s: String?): MoodTier? = entries.firstOrNull { it.label == s }

        /** A day's colour by vote; a tie between bright and dim is plain, not forced either way. */
        fun ofDay(tiers: List<MoodTier>): MoodTier? {
            if (tiers.isEmpty()) return null
            val bright = tiers.count { it == BRIGHT }
            val dim = tiers.count { it == DIM }
            return when {
                bright > dim -> BRIGHT
                dim > bright -> DIM
                else -> PLAIN
            }
        }
    }
}

object MurmurLabels {
    const val SYSTEM = "给碎碎念打标签：意图|情绪|方面|提及|高光"

    val INTENTS = listOf("记录", "待办", "查询")
    val MOODS = listOf("开心", "满足", "感动", "期待", "平静", "好奇", "疲惫", "低落", "焦虑", "烦躁", "生气", "难受")
    val ASPECTS = listOf(
        "吃喝", "身体", "运动", "工作", "学习阅读", "影音娱乐", "家人", "朋友社交", "伴侣", "孩子",
        "宠物", "自然天气", "出行", "居家", "钱", "创作爱好", "想法",
    )
    val MENTION_TYPES = listOf("吃喝", "书影", "音乐", "人", "地点", "症状", "药", "运动", "数量", "花销", "植物", "动物")

    /** Murmurs are cut to this before tagging, as in training. */
    const val MAX_CHARS = 300

    /**
     * Sent to the cloud for a second look when the model scores at least this
     * (§19.3). On the author's 244 real murmurs: with [KEYWORDS], 95% of the
     * ones that needed doing, about a fifth of all.
     */
    const val ACT_THRESHOLD = 0.02f

    /** Plain words that usually mean "to do" or "a question"; OR-ed with the model's score. */
    val KEYWORDS = Regex("(明天|后天|今晚|下周|周[一二三四五六日末]|记得|提醒|别忘|得去|要去|得修|得买|准备|[吗呢]？|？$|怎么|哪个|什么.{0,4}[呀呢]|查一下|帮我)")

    fun isCandidate(text: String, tags: MurmurTags): Boolean =
        tags.intent != "记录" || tags.actScore >= ACT_THRESHOLD || KEYWORDS.containsMatchIn(text)

    fun prompt(text: String): String =
        "<|im_start|>system\n$SYSTEM<|im_end|>\n<|im_start|>user\n${text.take(MAX_CHARS)}<|im_end|>\n" +
            "<|im_start|>assistant\n<think>\n\n</think>\n\n"

    val GRAMMAR: String = run {
        fun alt(xs: List<String>) = xs.joinToString(" | ") { "\"$it\"" }
        listOf(
            "root ::= intent \"|\" mood \"|\" aspects \"|\" mentions \"|\" hl",
            "intent ::= ${alt(INTENTS)}",
            "mood ::= ${alt(MOODS)}",
            "aspect ::= ${alt(ASPECTS)}",
            "aspects ::= aspect (\",\" aspect)?",
            "mtype ::= ${alt(MENTION_TYPES)}",
            "mval ::= [^|;:\\n]{1,24}",
            "mention ::= mtype \":\" mval",
            "mentions ::= \"无\" | mention (\";\" mention){0,4}",
            "hl ::= \"是\" | \"否\"",
        ).joinToString("\n")
    }

    private fun squash(s: String) = s.replace(Regex("\\s+"), "")

    /**
     * Parse a tag line; null if it is not one. Mentions whose words are not in
     * [text] are dropped (whitespace-insensitive) — the model may only point
     * at what was written, never add to it.
     */
    fun parse(line: String, text: String, actScore: Float): MurmurTags? {
        val p = line.trim().split("|")
        if (p.size != 5) return null
        val (intent, mood, aspects, mentions, hl) = p
        if (intent !in INTENTS || mood !in MOODS || hl !in setOf("是", "否")) return null
        val asp = aspects.split(",").filter { it in ASPECTS }.distinct()
        if (asp.isEmpty()) return null
        val flat = squash(text)
        val men = if (mentions == "无") emptyList() else mentions.split(";").mapNotNull { m ->
            val i = m.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val t = m.substring(0, i)
            val v = m.substring(i + 1).trim()
            if (t !in MENTION_TYPES || v.isEmpty()) return@mapNotNull null
            if (v in text || squash(v) in flat) t to v else null
        }.distinctBy { it.second }
        return MurmurTags(intent, mood, asp.take(2), men.take(5), hl == "是", actScore)
    }
}
