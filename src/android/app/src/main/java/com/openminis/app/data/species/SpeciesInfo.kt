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
 * user's own photos (SPEC §17.4), fetched once and kept:
 * - a Wikipedia summary, in the user's language, found by the scientific
 *   name (a Chinese common name can land on the wrong article);
 * - how many iNaturalist sightings in China fall in each month, i.e. when
 *   it is about.
 *
 * Only the scientific name and the iNaturalist id leave the phone — never a
 * photo or a place. Kept in filesDir/species-info/, refreshed after
 * [MAX_AGE_MS]; offline, the last copy is shown.
 */
object SpeciesInfo {
    private const val TAG = "SpeciesInfo"
    private const val MAX_AGE_MS = 90L * 24 * 3600 * 1000
    private const val UA = "Minis/1.0 (Android; species info for the user's own nature collection)"
    private const val CHINA = 6903

    data class Info(
        /** Plain-text summary, null if no article was found. */
        val summary: String?,
        val articleUrl: String?,
        /** The species has no article; [summary] is about its genus. */
        val aboutGenus: Boolean,
        /** Sightings in China per month, January first; null if unknown. */
        val months: List<Int>?,
        val fetchedAt: Long,
    )

    private val UNSAFE = Regex("[^A-Za-z0-9 ._-]")

    private fun file(context: Context, latin: String) =
        File(File(context.filesDir, "species-info"), latin.replace(UNSAFE, "_") + "." + lang() + ".json")

    /** Wikipedia edition and script for the current locale: zh-cn / zh-tw / en. */
    private fun lang(): String {
        val l = Locale.getDefault()
        if (l.language != "zh") return "en"
        val trad = l.script == "Hant" || l.country in setOf("TW", "HK", "MO")
        return if (trad) "zh-tw" else "zh-cn"
    }

    /** The cached copy, without touching the network. */
    fun cached(context: Context, latin: String): Info? = runCatching {
        val o = JSONObject(file(context, latin).readText())
        Info(
            summary = o.optString("summary").ifBlank { null },
            articleUrl = o.optString("url").ifBlank { null },
            aboutGenus = o.optBoolean("genus"),
            months = o.optJSONArray("months")?.let { a -> List(a.length()) { a.getInt(it) } },
            fetchedAt = o.optLong("at"),
        )
    }.getOrNull()

    /** Cached if fresh, else fetched (keeping the old copy when the network fails). */
    suspend fun get(context: Context, t: SpeciesId.Taxon): Info? = withContext(Dispatchers.IO) {
        val latin = t.latin
        val old = cached(context, latin)
        // Fresh enough — unless it was kept before the months could be asked for (no iNat id then).
        if (old != null && System.currentTimeMillis() - old.fetchedAt < MAX_AGE_MS && (old.months != null || t.inatId == null)) return@withContext old
        val wiki = runCatching { article(t) }.onFailure { AppLogger.warning(TAG, "wikipedia: ${it.message}") }.getOrNull()
        val months = t.inatId?.let { id -> runCatching { months(id) }.onFailure { AppLogger.warning(TAG, "inat: ${it.message}") }.getOrNull() }
        if (wiki == null && months == null) return@withContext old
        val info = Info(
            summary = wiki?.summary ?: old?.summary,
            articleUrl = wiki?.url ?: old?.articleUrl,
            aboutGenus = wiki?.genus ?: old?.aboutGenus ?: false,
            months = months ?: old?.months,
            fetchedAt = System.currentTimeMillis(),
        )
        runCatching {
            file(context, latin).also { it.parentFile?.mkdirs() }.writeText(
                JSONObject().put("summary", info.summary ?: "").put("url", info.articleUrl ?: "").put("genus", info.aboutGenus)
                    .put("months", info.months?.let { JSONArray(it) }).put("at", info.fetchedAt).toString(),
            )
        }
        info
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
                JSONObject(fetch("https://api.inaturalist.org/v1/taxa/$inatId", null)).optJSONArray("results")?.optJSONObject(0)
            } else {
                val r = JSONObject(fetch("https://api.inaturalist.org/v1/taxa?per_page=5&q=" + URLEncoder.encode(latin, "UTF-8"), null)).optJSONArray("results")
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

    private class Article(val summary: String?, val url: String?, val genus: Boolean)

    /**
     * The species' own article, else its genus' (flagged), else nothing. In
     * Chinese, our own Chinese name is the safest title (家猫 → 猫); a search
     * by the scientific name is accepted only if the article names it in
     * Chinese too — the top hit for "Felis catus" is 猫属, the genus.
     */
    private fun article(t: SpeciesId.Taxon): Article {
        val lang = lang()
        val host = if (lang == "en") "en.wikipedia.org" else "zh.wikipedia.org"
        fun find(latin: String, zh: String?): Pair<String, String?>? {
            if (lang != "en" && zh != null) summary(host, zh, lang)?.let { return it }
            val title = search(host, latin, lang) ?: return null
            val hit = summary(host, title, lang) ?: return null
            return if (lang == "en" || zh == null || zh in title || zh in hit.first) hit else null
        }
        find(t.latin, t.zh)?.let { return Article(it.first, it.second, genus = false) }
        if (' ' in t.latin) find(t.genusLatin, t.genusZh)?.let { return Article(it.first, it.second, genus = true) }
        return Article(null, null, genus = false)
    }

    private fun search(host: String, phrase: String, lang: String): String? {
        val q = URLEncoder.encode("\"$phrase\"", "UTF-8")
        val o = JSONObject(fetch("https://$host/w/api.php?action=query&list=search&srsearch=$q&srlimit=1&format=json", lang))
        return o.optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)?.optString("title")?.ifBlank { null }
    }

    /** (extract, url) of a page, following redirects; null for missing pages and disambiguations. */
    private fun summary(host: String, title: String, lang: String): Pair<String, String?>? {
        val body = runCatching { fetch("https://$host/api/rest_v1/page/summary/" + URLEncoder.encode(title, "UTF-8").replace("+", "%20"), lang) }.getOrNull()
            ?: return null
        val s = JSONObject(body)
        if (s.optString("type") != "standard") return null
        val text = s.optString("extract").ifBlank { return null }
        return text to s.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page")
    }

    private fun months(inatId: Long): List<Int>? {
        val o = JSONObject(fetch("https://api.inaturalist.org/v1/observations/histogram?taxon_id=$inatId&place_id=$CHINA&interval=month_of_year&verifiable=true", null))
        val m = o.optJSONObject("results")?.optJSONObject("month_of_year") ?: return null
        return (1..12).map { m.optInt(it.toString()) }
    }

    /** Same shape as the other small API clients (WeatherManager): short timeouts, a small reused pool. */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
        .build()

    private fun fetch(url: String, lang: String?): String {
        val req = Request.Builder().url(url).header("User-Agent", UA)
        if (lang != null && lang != "en") req.header("Accept-Language", lang)
        client.newCall(req.build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            return r.body?.string().orEmpty()
        }
    }
}
