package com.openminis.app.data.murmur

import android.content.Context
import com.openminis.app.data.journal.JournalStore
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [T-today-murmur] What the model and the cloud said about each murmur, by
 * entry id (SPEC §19.1, §19.3). Not in the journal files:
 *
 * - **tags** are derived — redone when the text or the model changes, so they
 *   live in noBackupFilesDir and may be thrown away at any time;
 * - **suggestions** cost a cloud call and carry the user's answer ("不用",
 *   a reminder set), so they live in filesDir.
 *
 * A user's own mood correction is neither: it is written into the entry
 * (`mood=` attribute, §19.2), because it is the user's data.
 */
class MurmurIndex private constructor(context: Context) {
    private val tagsFile = File(context.noBackupFilesDir, "murmur/tags.json")
    private val suggestionsFile = File(context.filesDir, "murmur/suggestions.json")
    private val tags = HashMap<String, Pair<String, MurmurTags>>()
    private val suggestions = HashMap<String, MurmurSuggestion>()

    private val _tagChanges = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Goes up each time a murmur's tags are written (探索 rebuilds on it, §20). */
    val tagChanges: kotlinx.coroutines.flow.StateFlow<Int> = _tagChanges

    init {
        read(tagsFile)?.let { o ->
            for (id in o.keys()) {
                val e = o.getJSONObject(id)
                if (e.optString("v") != MurmurLlm.MODEL_VERSION) continue
                tags[id] = e.getString("h") to MurmurTags(
                    intent = e.getString("i"),
                    mood = e.getString("m"),
                    aspects = e.getJSONArray("a").let { a -> List(a.length()) { a.getString(it) } },
                    mentions = e.getJSONArray("n").let { a -> List(a.length()) { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } } },
                    highlight = e.getBoolean("hl"),
                    actScore = e.getDouble("p").toFloat(),
                )
            }
        }
        read(suggestionsFile)?.let { o -> for (id in o.keys()) MurmurSuggestion.from(o.getJSONObject(id))?.let { suggestions[id] = it } }
    }

    @Synchronized fun tags(id: String, body: String): MurmurTags? = tags[id]?.takeIf { it.first == hash(body) }?.second

    @Synchronized fun putTags(id: String, body: String, t: MurmurTags) {
        tags[id] = hash(body) to t
        _tagChanges.value++
        // Tags are derived and may be lost, so writes are gathered: a backlog of
        // hundreds is one write every few seconds, not one whole-file rewrite each.
        if (!saveScheduled) {
            saveScheduled = true
            saver.schedule(::saveTags, SAVE_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private var saveScheduled = false
    private val saver = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "murmur-tags").apply { isDaemon = true } }

    private fun saveTags() {
        val o = synchronized(this) {
            saveScheduled = false
            JSONObject().apply {
                for ((k, v) in tags) put(k, JSONObject().apply {
                    val x = v.second
                    put("v", MurmurLlm.MODEL_VERSION); put("h", v.first)
                    put("i", x.intent); put("m", x.mood); put("a", JSONArray(x.aspects))
                    put("n", JSONArray(x.mentions.map { JSONArray(listOf(it.first, it.second)) }))
                    put("hl", x.highlight); put("p", x.actScore.toDouble())
                })
            }
        }
        write(tagsFile, o)
    }

    /** The suggestion for this text; one made for an earlier version of the text no longer applies. */
    @Synchronized fun suggestion(id: String, body: String): MurmurSuggestion? = suggestions[id]?.takeIf { it.hash == hash(body) }

    @Synchronized fun allSuggestions(): Map<String, MurmurSuggestion> = HashMap(suggestions)

    @Synchronized fun putSuggestion(id: String, s: MurmurSuggestion) {
        suggestions[id] = s
        write(suggestionsFile, JSONObject().apply { for ((k, v) in suggestions) put(k, v.toJson()) })
    }

    private fun read(f: File): JSONObject? = if (!f.isFile) null else runCatching { JSONObject(f.readText()) }
        .onFailure { AppLogger.warning(TAG, "unreadable ${f.name}: ${it.message}") }.getOrNull()

    private fun write(f: File, o: JSONObject) = runCatching { JournalStore.writeAtomically(f, o.toString()) }
        .onFailure { AppLogger.warning(TAG, "write ${f.name}: ${it.message}") }

    companion object {
        private const val TAG = "MurmurIndex"
        private const val SAVE_DELAY_MS = 3_000L
        @Volatile private var instance: MurmurIndex? = null

        fun get(context: Context): MurmurIndex = instance ?: synchronized(this) {
            instance ?: MurmurIndex(context.applicationContext).also { instance = it }
        }

        fun hash(body: String): String = MessageDigest.getInstance("SHA-1").digest(body.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
    }
}

/**
 * One cloud verdict on a murmur (§19.3) and what the user did with it.
 * [state]: [OFFER] shown under the murmur; [NONE] nothing to suggest;
 * [DISMISSED] "不用"; [SCHEDULED] reminder set.
 */
data class MurmurSuggestion(
    val hash: String,
    val state: String,
    val type: String = "",
    /** Reminder: what to remind of, and when (epoch ms). */
    val title: String = "",
    val atMs: Long = 0,
    /** Answer: the question as the cloud read it, and its answer. */
    val question: String = "",
    val answer: String = "",
    /** Ask Minis: the message to start the chat with. */
    val prompt: String = "",
    val createdMs: Long = System.currentTimeMillis(),
) {
    fun toJson() = JSONObject().apply {
        put("h", hash); put("s", state); put("t", type); put("title", title); put("at", atMs)
        put("q", question); put("ans", answer); put("prompt", prompt); put("c", createdMs)
    }

    companion object {
        const val OFFER = "offer"
        const val NONE = "none"
        const val DISMISSED = "dismissed"
        const val SCHEDULED = "scheduled"
        const val TYPE_REMINDER = "reminder"
        const val TYPE_ANSWER = "answer"
        const val TYPE_ASK = "ask_minis"

        fun from(o: JSONObject): MurmurSuggestion? = runCatching {
            MurmurSuggestion(
                hash = o.getString("h"), state = o.getString("s"), type = o.optString("t"), title = o.optString("title"),
                atMs = o.optLong("at"), question = o.optString("q"), answer = o.optString("ans"), prompt = o.optString("prompt"),
                createdMs = o.optLong("c"),
            )
        }.getOrNull()
    }
}
