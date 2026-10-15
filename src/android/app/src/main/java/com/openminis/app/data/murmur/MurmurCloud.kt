package com.openminis.app.data.murmur

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * [T-today-murmur] The cloud's second look at a murmur the local model thinks
 * may need doing (SPEC §19.3): one short call to the user's own model, not an
 * agent turn. Only the words and their time go out — no place, no photos, no
 * other murmurs. Returns null when there is no usable model or the call fails;
 * the caller does not retry.
 */
object MurmurCloud {
    private const val TAG = "MurmurCloud"

    private val SYSTEM = """
你是 Minis，用户手机日记「今日」里的助手。用户随手写了一条碎碎念，本地小模型觉得它可能需要处理。请你判断，并且只输出一个 JSON 对象，不要输出别的文字。

四种结果：
1. 用户接下来要做一件具体的事，提醒会有帮助：
   {"type":"reminder","title":"不超过12个字的事情","at":"yyyy-MM-ddTHH:mm"}
   - at 用当地时间，必须晚于"现在"。原话有时间就按原话（"晚上睡觉前"取 22:30，"明天"没说几点取明天 09:00，"中午"取 12:00）；已经过去了就不要提醒
2. 用户想知道一个答案，你现在就能简短回答（认东西、问做法、问原因、问事实）：
   {"type":"answer","question":"用户的问题","answer":"不超过120字的回答，拿不准就说可能，并说怎么确认"}
   - 涉及身体不适的，给常识性建议，提醒必要时就医，不下诊断
3. 需要花时间查资料、推荐、规划，适合在聊天里继续：
   {"type":"ask_minis","prompt":"以用户口吻写给 Minis 的一句话请求"}
4. 其余情况，包括感慨、反问、愿望、已经做完的事、观点：
   {"type":"none"}

宁可选 none，也不要打扰用户。用中文。
""".trimIndent()

    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE", Locale.CHINA)

    suspend fun ask(context: Context, text: String, writtenAtMs: Long, hash: String): MurmurSuggestion? {
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return null
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val written = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(writtenAtMs), zone)
        val prompt = "现在：${FMT.format(now)}\n写于：${FMT.format(written)}\n碎碎念：\n${text.take(600)}"
        for (entry in candidates(repo)) {
            val instance = repo.instance(entry.providerInstanceId) ?: continue
            var apiKey = repo.usableApiKey(instance) ?: if (repo.hasAnyCredential(instance)) "" else continue
            if (instance.credentialType == ProviderCredential.oauth) {
                runCatching { com.openminis.app.auth.OAuthManager.forInstance(context, instance)?.validAccessToken() }
                    .getOrNull()?.let { t -> if (t != apiKey) { repo.saveApiKey(instance.id, t); apiKey = t } }
            }
            val provider = runCatching { ProviderFactory.create(instance, apiKey, entry.model, context, overrides = entry.overrides) }
                .getOrNull() ?: continue
            try {
                val response = provider.sendMessage(
                    messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                    systemPrompt = SYSTEM,
                    // A reasoning model needs room to think before the JSON, even with thinking OFF.
                    maxTokens = if (entry.model.supportsReasoning == true) 2048 else 400,
                    temperature = null,
                    thinkingLevel = ThinkingLevel.OFF,
                )
                val s = parse(response.text, hash, now)
                if (s != null) {
                    AppLogger.info(TAG, "ok model=${entry.model.id} type=${s.type.ifEmpty { "none" }}")
                    return s
                }
                AppLogger.warning(TAG, "unparseable from ${entry.model.id}: ${response.text.take(120)}")
            } catch (e: Exception) {
                AppLogger.warning(TAG, "via ${entry.model.id} failed: ${e.message}")
            }
        }
        return null
    }

    /** A model with a credential exists to ask. */
    fun hasModel(context: Context): Boolean =
        (context.applicationContext as? MinisApp)?.providerRepositoryOrNull?.let { repo ->
            candidates(repo).any { e -> repo.instance(e.providerInstanceId)?.let { repo.usableApiKey(it) != null || repo.hasAnyCredential(it) } == true }
        } == true

    /** The title sub-model first (cheap), then the default chat model, then anything that writes text. */
    private fun candidates(repo: ProviderRepository): List<com.openminis.app.data.model.ModelEntry> {
        val eligible = repo.allVisibleEntries().filter { e ->
            val outs = e.model.outputModalities
            val id = e.model.id.lowercase()
            (outs == null || outs.isEmpty() || outs.contains("text")) &&
                listOf("tts", "voiceclone", "voicedesign", "embedding", "embed-", "whisper", "image", "video").none { id.contains(it) }
        }
        val sub = repo.resolveTitleSubEntry()?.takeIf { it in eligible }
        val primary = repo.defaultPrimaryGroupId?.let(repo::group)?.let(repo::availableMemberEntries).orEmpty().filter { it in eligible }
        return (listOfNotNull(sub) + primary + eligible).distinct().take(3)
    }

    internal fun parse(raw: String, hash: String, now: ZonedDateTime): MurmurSuggestion? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return null
        val none = MurmurSuggestion(hash, MurmurSuggestion.NONE)
        return when (o.optString("type")) {
            "none" -> none
            MurmurSuggestion.TYPE_REMINDER -> {
                val title = o.optString("title").trim().take(24)
                val at = runCatching { LocalDateTime.parse(o.optString("at").trim().take(16)) }.getOrNull()
                    ?.atZone(now.zone)
                if (title.isEmpty() || at == null || !at.isAfter(now)) none
                else MurmurSuggestion(hash, MurmurSuggestion.OFFER, MurmurSuggestion.TYPE_REMINDER, title = title, atMs = at.toInstant().toEpochMilli())
            }
            MurmurSuggestion.TYPE_ANSWER -> {
                val a = o.optString("answer").trim()
                if (a.isEmpty()) none
                else MurmurSuggestion(hash, MurmurSuggestion.OFFER, MurmurSuggestion.TYPE_ANSWER, question = o.optString("question").trim(), answer = a)
            }
            MurmurSuggestion.TYPE_ASK -> {
                val p = o.optString("prompt").trim()
                if (p.isEmpty()) none else MurmurSuggestion(hash, MurmurSuggestion.OFFER, MurmurSuggestion.TYPE_ASK, prompt = p)
            }
            else -> null
        }
    }
}
