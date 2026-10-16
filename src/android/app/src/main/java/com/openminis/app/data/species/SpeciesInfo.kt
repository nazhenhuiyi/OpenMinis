package com.openminis.app.data.species

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * [T-today-species] What the species page shows about a kind beyond the
 * user's own photos (SPEC §17.4), all from the files beside the model — no
 * network, so it works where Wikipedia can't be reached:
 * - the lead of its Wikipedia article in the user's language, else its
 *   genus' (species_wiki.<lang>.tsv, made by docs/design/today/species/wiki.py);
 * - when it is about: its iNaturalist sightings in China by month, from
 *   species_prior.bin, and how many there are in all (species.tsv).
 *
 * Reference photos are still fetched from iNaturalist (only the scientific
 * name and its id leave the phone — never a photo or a place).
 */
object SpeciesInfo {
    private const val TAG = "SpeciesInfo"
    private const val UA = "Minis/1.0 (Android; species info for the user's own nature collection)"

    data class Info(
        /** Plain-text summary, null if no article was found. */
        val summary: String?,
        val articleUrl: String?,
        /** The species has no article; [summary] is about its genus. */
        val aboutGenus: Boolean,
        /** Share of its sightings in China per month (of the busiest, 0–255), January first; null if unknown. */
        val months: List<Int>?,
        /** Its sightings in China, all months. */
        val sightings: Int,
    )

    private val UNSAFE = Regex("[^A-Za-z0-9 ._-]")

    /** Wikipedia edition and script for the current locale: zh-cn / zh-tw / en. */
    private fun lang(): String {
        val l = Locale.getDefault()
        if (l.language != "zh") return "en"
        val trad = l.script == "Hant" || l.country in setOf("TW", "HK", "MO")
        return if (trad) "zh-tw" else "zh-cn"
    }

    suspend fun get(context: Context, t: SpeciesId.Taxon): Info = withContext(Dispatchers.IO) {
        val lang = lang()
        val wiki = runCatching { article(context, t, lang) }.onFailure { AppLogger.warning(TAG, "wikipedia: ${it.message}") }.getOrNull()
        Info(
            summary = wiki?.third,
            articleUrl = wiki?.let { url(lang, it.second) },
            aboutGenus = wiki != null && wiki.first != t.latin,
            months = SpeciesId.months(context, t.latin),
            sightings = t.count,
        )
    }

    private fun article(context: Context, t: SpeciesId.Taxon, lang: String): Triple<String, String, String>? {
        val f = SpeciesId.dir(context)?.let { File(it, "species_wiki.$lang.tsv") }?.takeIf { it.isFile } ?: return null
        return article(f, t.latin, t.genusLatin.takeIf { ' ' in t.latin })
    }

    /**
     * (key, title, lead) of [latin]'s own article, else [genus]' — a genus
     * suggestion is its own key. Lines are `latin \t title \t lead`; one
     * pass over the file finds both.
     */
    internal fun article(f: File, latin: String, genus: String?): Triple<String, String, String>? {
        var ofGenus: Triple<String, String, String>? = null
        f.useLines { lines ->
            for (line in lines) {
                val key = line.substringBefore('\t')
                if (key != latin && key != genus) continue
                val c = line.split('\t', limit = 3)
                if (c.size < 3) continue
                if (key == latin) return Triple(key, c[1], c[2])
                ofGenus = Triple(key, c[1], c[2])
            }
        }
        return ofGenus
    }

    private fun url(lang: String, title: String): String {
        val path = URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
        return if (lang == "en") "https://en.m.wikipedia.org/wiki/$path" else "https://zh.m.wikipedia.org/$lang/$path"
    }

    // ── Reference photos ─────────────────────────────────────────────────

    /** A photo of the kind from iNaturalist, openly licensed, with its credit line (shown when opened). */
    data class RefPhoto(val url: String, val largeUrl: String, val attribution: String)

    private const val PHOTOS_MAX_AGE_MS = 180L * 24 * 3600 * 1000
    private val refMemory = java.util.concurrent.ConcurrentHashMap<String, List<RefPhoto>>()

    /**
     * Up to [max] photos of [latin] — what it looks like, to set beside the
     * user's own when the answer is unsure (§17.3). By iNaturalist id when we
     * have it, else by exact name (genera, hand-added kinds). Only photos
     * under an open licence; all-rights-reserved ones are skipped. Kept for
     * [PHOTOS_MAX_AGE_MS]; the images themselves are cached by the image loader.
     */
    suspend fun refPhotos(context: Context, latin: String, inatId: Long?, max: Int = 3): List<RefPhoto> = withContext(Dispatchers.IO) {
        refMemory[latin]?.let { return@withContext it }
        val f = File(File(context.filesDir, "species-info"), latin.replace(UNSAFE, "_") + ".photos.json")
        val kept = runCatching {
            val o = JSONObject(f.readText())
            if (System.currentTimeMillis() - o.optLong("at") > PHOTOS_MAX_AGE_MS) null
            else o.getJSONArray("photos").let { a -> List(a.length()) { a.getJSONObject(it) }.map { RefPhoto(it.getString("url"), it.getString("large"), it.getString("by")) } }
        }.getOrNull()
        if (kept != null) return@withContext kept.also { refMemory[latin] = it }
        val fetched = runCatching {
            val taxon = if (inatId != null) {
                JSONObject(fetch("https://api.inaturalist.org/v1/taxa/$inatId")).optJSONArray("results")?.optJSONObject(0)
            } else {
                val r = JSONObject(fetch("https://api.inaturalist.org/v1/taxa?per_page=5&q=" + URLEncoder.encode(latin, "UTF-8"))).optJSONArray("results")
                (0 until (r?.length() ?: 0)).map { r!!.getJSONObject(it) }.firstOrNull { it.optString("name") == latin }
            } ?: return@runCatching emptyList()
            val all = buildList {
                taxon.optJSONObject("default_photo")?.let { add(it) }
                taxon.optJSONArray("taxon_photos")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optJSONObject("photo")?.let { add(it) } }
            }
            all.filter { !it.isNull("license_code") && it.optString("medium_url").isNotBlank() }
                .distinctBy { it.optLong("id") }
                .take(max)
                .map { p -> p.optString("medium_url").let { m -> RefPhoto(m, m.replace("/medium.", "/large."), p.optString("attribution")) } }
        }.onFailure { AppLogger.warning(TAG, "reference photos: ${it.message}") }.getOrNull() ?: return@withContext emptyList()
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(JSONObject().put("at", System.currentTimeMillis()).put("photos", JSONArray(fetched.map {
                JSONObject().put("url", it.url).put("large", it.largeUrl).put("by", it.attribution)
            })).toString())
        }
        fetched.also { refMemory[latin] = it }
    }

    /** Same shape as the other small API clients (WeatherManager): short timeouts, a small reused pool. */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
        .build()

    private fun fetch(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA)
        client.newCall(req.build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            return r.body?.string().orEmpty()
        }
    }
}
