package com.openminis.app.data.murmur

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * [T-today-murmur-agent] Smart suggestions (SPEC §19.3): a murmur that passed
 * the on-device sift is handed to Minis itself, in a chat started in the
 * background and kept out of the session lists. Minis looks into it — it can
 * search, read memory — but in this turn only prepares (see
 * ChatViewModel.murmurBrief): no reminders set, nothing written or sent.
 *
 * Its answer is either "不需要" (the chat is deleted, nothing shows) or a
 * one-line summary first, which shows under the murmur. Tapping it opens the
 * chat, now an ordinary one in the list, to carry on from there.
 */
object MurmurAgent {
    private const val TAG = "MurmurAgent"

    /**
     * Bump when [BRIEF] changes in a way that should reach murmurs already
     * judged: a "none" from an older version is asked again while the murmur is
     * still within the suggestion window. 3: Minis in a chat (1–2 were the
     * one-shot cloud prompt). 4: lean towards helping; curiosity always counts
     * (3 let an open question about world events go as a musing). 5: the summary is
     * the first line of Minis's final text, not of everything it said on the way.
     * 6: the summary is marked "摘要：" — told to "write a summary first", Minis
     * still opened with "查完了，我的判断如下。".
     */
    const val BRIEF_VERSION = 6

    private const val SUMMARY = "摘要："

    /** How long the background turn may take before it is given up. */
    private const val TIMEOUT_MS = 4 * 60 * 1000L

    private const val NOTHING = "不需要"

    /** For this one background turn only; appended to Minis's system prompt. */
    private val BRIEF = """
        |About this conversation: it was started automatically from a murmur the user just wrote in Today (their journal). The user is NOT here right now; they will see your first line under the murmur and may tap it to continue this chat.
        |
        |1. This murmur already passed a filter for ones that may need help, so lean towards helping. Worth doing: something to do later (offer a reminder), something they want to know (answer it), curiosity — any "好奇 / 想知道 / 为什么 / 会不会", asked out loud or not — (explain briefly, or lay out the main views when there is no single answer), or something they would like to do (find out what helps: dates, opening hours, how to start).
        |2. Only when it is purely a feeling, or something already done, reply with exactly "$NOTHING" and nothing else.
        |3. Otherwise, your final reply MUST begin with a line "$SUMMARY" followed by at most 30 Chinese characters that carry the actual point and make sense on their own under the murmur — not an opener like "查完了" or "我的判断如下" (e.g. "${SUMMARY}今晚 22:30 提醒你吃布洛芬？", "${SUMMARY}叶子半黄半绿多半是品种差异", "${SUMMARY}学画画可以先从每天十分钟速写开始"). Then a blank line, then the details.
        |4. In this turn only look things up and prepare: you may use the shell to search and read, but do not set reminders or calendar events, send messages, or write files or memories. If something should be done, propose it and wait for their reply.
        |5. Reply in Chinese.
    """.trimMargin()

    private val FMT = DateTimeFormatter.ofPattern("M月d日 EEEE HH:mm", Locale.CHINA)

    /** Whether Minis has a model to run on unattended; without one the switch says so. */
    fun hasModel(context: Context): Boolean =
        (context.applicationContext as? MinisApp)?.providerRepositoryOrNull?.let(::unattendedModel) != null

    /**
     * The model a background run starts on, as ScheduledAgentRunner picks it:
     * the default group's first member that has credentials (with the group
     * binding to write), else the first visible entry whose provider has them.
     * Credentials are checked once per provider, not per entry.
     */
    private fun unattendedModel(repo: ProviderRepository): Pair<String, String?>? {
        repo.defaultPrimaryGroupId?.let { gid ->
            repo.group(gid)?.let(repo::availableMemberEntries)?.firstOrNull()?.model?.id
                ?.let { return it to """{"type":"group","groupId":"$gid"}""" }
        }
        val usable = HashMap<String, Boolean>()
        return repo.allVisibleEntries().firstOrNull { e ->
            usable.getOrPut(e.providerInstanceId) { repo.instance(e.providerInstanceId)?.let(repo::hasAnyCredential) == true }
        }?.baseModel?.id?.let { it to null }
    }

    /**
     * Run Minis on [body] in a hidden chat. Returns the suggestion to keep — an
     * offer with the chat, or "none" (the chat already deleted) — or null when it
     * could not run (no model, the app not ready, a failure or timeout: tried
     * again another time).
     */
    suspend fun prepare(context: Context, body: String, writtenAtMs: Long, hash: String): MurmurSuggestion? {
        val app = context.applicationContext as? MinisApp ?: return null
        if (!app.subsystemsReady()) return null
        val (model, binding) = unattendedModel(app.providerRepository) ?: return null
        val zone = ZoneId.systemDefault()
        val written = ZonedDateTime.ofInstant(Instant.ofEpochMilli(writtenAtMs), zone)
        val sessionId = withContext(Dispatchers.IO) {
            val s = app.chatRepository.createSession(
                modelId = model,
                title = "碎碎念 · " + body.replace(Regex("\\s+"), " ").take(16),
                memoryEnabled = com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(app),
            )
            app.chatRepository.dao.updateSource(s.id, ChatSessionEntity.SOURCE_MURMUR_PENDING)
            binding?.let { app.chatRepository.updateSessionBinding(s.id, it, model) }
            s.id
        }
        // What the user will see as the chat's first message once they open it.
        val prompt = buildString {
            append("我在「今日」写了一条碎碎念（").append(FMT.format(written)).append("）：\n\n")
            body.lines().forEach { append("> ").append(it).append('\n') }
            append("\n帮我看看有没有我用得上的。")
        }
        return try {
            withContext(Dispatchers.Main) { HeadlessChatRunner.viewModelFor(context, sessionId).murmurBrief = BRIEF }
            val r = HeadlessChatRunner.prompt(context, sessionId, prompt, wait = true, timeoutMs = TIMEOUT_MS)
            // The reply proper: what Minis wrote last, after any looking up ("数据齐了……" on the way is not it).
            val text = finalText(app, sessionId) ?: r.responseText?.trim()
            when {
                r.status != "Completed" || text.isNullOrEmpty() -> {
                    AppLogger.warning(TAG, "run ended ${r.status}")
                    discard(context, sessionId)
                    null
                }
                text.startsWith(NOTHING) && text.length <= NOTHING.length + 4 -> {
                    AppLogger.info(TAG, "nothing to do")
                    discard(context, sessionId)
                    MurmurSuggestion(hash, MurmurSuggestion.NONE, promptVersion = BRIEF_VERSION)
                }
                else -> {
                    AppLogger.info(TAG, "offer ready")
                    MurmurSuggestion(
                        hash, MurmurSuggestion.OFFER,
                        title = headline(text), preview = preview(text), sessionId = sessionId, promptVersion = BRIEF_VERSION,
                    )
                }
            }
        } catch (e: Exception) {
            // Even when cancelled (Today's scope gone), the half-made chat must not stay behind.
            withContext(NonCancellable) { runCatching { discard(context, sessionId) } }
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.warning(TAG, "failed: ${e.message}")
            null
        } finally {
            HeadlessChatRunner.existingViewModel(sessionId)?.murmurBrief = null
        }
    }

    /** The last text Minis wrote in [sessionId]: the final text part of its last message. */
    private suspend fun finalText(app: MinisApp, sessionId: String): String? = withContext(Dispatchers.IO) {
        val parts = app.chatRepository.dao.lastAssistantParts(sessionId) ?: return@withContext null
        runCatching {
            val arr = org.json.JSONArray(parts)
            (arr.length() - 1 downTo 0).asSequence()
                .mapNotNull { arr.optJSONObject(it) }
                .firstOrNull { it.optString("type") == "text" && it.optString("value").isNotBlank() }
                ?.optString("value")?.trim()
        }.getOrNull()
    }

    /** What may mark the summary line (Minis does not always use the exact one asked for). */
    private val MARKS = listOf(SUMMARY, "摘要:", "总结：", "总结:")

    /** The reply's non-empty lines, without markdown marks. */
    private fun plainLines(reply: String): List<String> =
        reply.lineSequence().map { it.trim().trimStart('#', '>', '-', '*', ' ').trimEnd('*', ' ').replace("**", "") }
            .filter { it.isNotEmpty() }.toList()

    private fun summaryIndex(lines: List<String>): Int = lines.indexOfFirst { l -> MARKS.any(l::startsWith) }

    /** The summary for under the murmur: the marked line, else the first line; at most 40 characters. */
    internal fun headline(reply: String): String {
        val lines = plainLines(reply)
        val line = lines.getOrNull(summaryIndex(lines)) ?: lines.firstOrNull().orEmpty()
        return MARKS.fold(line) { l, m -> l.removePrefix(m) }.trim().take(40)
    }

    /** The first line of the details after the summary, at most 60 characters ("" if none). */
    internal fun preview(reply: String): String {
        val lines = plainLines(reply)
        return lines.drop(summaryIndex(lines).coerceAtLeast(0) + 1).firstOrNull().orEmpty().take(60)
    }

    /** The user tapped it: from now on an ordinary chat, in the lists. */
    suspend fun open(context: Context, sessionId: String) = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? MinisApp ?: return@withContext
        app.chatRepository.dao.updateSource(sessionId, ChatSessionEntity.SOURCE_MURMUR)
    }

    /**
     * Cold start: murmur chats never opened and untouched for two days, wherever
     * they were left (a run killed between creating the chat and recording it,
     * Today not opened since). Today clears its own recorded ones sooner.
     */
    suspend fun sweep(context: Context) {
        val app = context.applicationContext as? MinisApp ?: return
        if (!app.subsystemsReady()) return
        val stale = app.chatRepository.dao.staleMurmurPendingIds(System.currentTimeMillis() - SWEEP_AFTER_MS)
        for (id in stale) app.chatRepository.deleteSession(id)
        if (stale.isNotEmpty()) AppLogger.info(TAG, "swept ${stale.size} unopened chat(s)")
    }

    private const val SWEEP_AFTER_MS = 2 * 24 * 60 * 60 * 1000L

    /** Never opened: gone, with its messages (a run still going is stopped first). */
    suspend fun discard(context: Context, sessionId: String) {
        val app = context.applicationContext as? MinisApp ?: return
        runCatching { HeadlessChatRunner.cancel(context, sessionId) }
        withContext(Dispatchers.IO) { app.chatRepository.deleteSession(sessionId) }
        HeadlessChatRunner.forget(sessionId)
    }
}
