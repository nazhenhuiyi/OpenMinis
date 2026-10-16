package com.openminis.app.data.species

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * [T-today-species] On-device species ID for Today's photos (SPEC §17):
 * BioCLIP 2's image encoder turns a photo into a 768-d embedding, which is
 * compared with precomputed embeddings of the species found in China. Nothing
 * leaves the phone.
 *
 * NPU first: the encoder runs on a Snapdragon's Hexagon NPU through ONNX
 * Runtime's QNN provider (~30 ms a look). Phones without one — or whose NPU
 * can't run it — get the feature only if the user switches on the CPU
 * ([setUseCpu]): the same model file on ONNX Runtime's CPU provider, about
 * 30× slower, so each photo is looked at only in the middle (twice, as it is
 * and mirrored). A phone whose NPU works never uses the CPU. Files live in
 * `getExternalFilesDir("species")` (see [REQUIRED]).
 *
 * The first load compiles the model for the NPU (~35 s) and caches the result
 * in noBackupFilesDir; every later load reads the cache (~0.4 s). The cache is
 * redone only for a new model file, cleared app data, or a cache that no
 * longer loads (e.g. after a QNN upgrade).
 */
object SpeciesId {
    private const val TAG = "SpeciesId"
    private const val SIZE = 224
    private const val DIM = 768
    private const val LOGIT_SCALE = 100f

    private const val NPU_MODEL = "bioclip2_img_w8a16.onnx"
    private const val EMBEDDINGS = "species.bin"
    private const val NAMES = "species.tsv"
    /** Optional: where and when each kind is seen (Table.Prior). */
    private const val PRIOR = "species_prior.bin"
    private const val ABSENT_WEIGHT = 0.4f
    private const val OFF_SEASON_FLOOR = 0.5f
    private val REQUIRED = listOf(NPU_MODEL, "$NPU_MODEL.data", EMBEDDINGS, NAMES)
    /** Encoder runs that fail in a row before the NPU is given up on (until the next app version). */
    private const val MAX_FAILURES = 3

    /** A suggestion is shown on its own only when the model is this sure. */
    private const val MIN_SPECIES_PROB = 0.5f
    private const val MIN_GENUS_PROB = 0.7f
    /** Below this the photo does not look like any organism we know (food, streets, skies…). */
    private const val MIN_MATCH = 0.70f
    private const val MIN_LIFE = 0.66f

    /**
     * Mostly photographed on a plate or in a cup, not in the wild: a latte
     * really does look like Coffea arabica to the model. Never suggested
     * unasked; "Identify" still names them.
     */
    private val FOOD = setOf(
        "Coffea arabica", "Coffea canephora", "Camellia sinensis", "Oryza sativa", "Triticum aestivum", "Zea mays",
        "Allium cepa", "Allium sativum", "Allium fistulosum", "Allium tuberosum", "Zingiber officinale", "Capsicum annuum",
        "Solanum lycopersicum", "Solanum tuberosum", "Solanum melongena", "Glycine max", "Brassica oleracea", "Brassica rapa",
        "Citrus sinensis", "Citrus reticulata", "Citrus limon", "Malus domestica", "Pyrus pyrifolia", "Musa acuminata",
        "Fragaria ananassa", "Vitis vinifera", "Ipomoea batatas", "Cucumis sativus", "Daucus carota", "Lactuca sativa",
        "Spinacia oleracea", "Arachis hypogaea", "Sesamum indicum", "Theobroma cacao", "Vanilla planifolia", "Hordeum vulgare",
    )
    private const val IDLE_RELEASE_MS = 90_000L
    /** Photos are decoded with their short side around this, before cropping. */
    private const val DECODE_SHORT = 1024
    /** A find replaces the middle only when this much surer (Mac test: fewer wrong switches than "always the surest"). */
    private const val SWITCH_MARGIN = 0.15f
    /** How photos are looked at; change it when that changes, so kept verdicts are redone. */
    private const val VIEW_STRATEGY = "grid3x3+2x2+centre/fullres+mirror/prior"

    /**
     * One species of the list. Beyond the names: its iNaturalist id, how many
     * verifiable sightings it has in China there ([count], a fair "how common"),
     * the ranks above it ([chain], kingdom → family, latin and Chinese), and
     * the rows of the list the model finds nearest ([lookalikes]).
     */
    data class Taxon(
        val latin: String,
        val zh: String?,
        val genusLatin: String,
        val genusZh: String?,
        val group: String?,
        val inatId: Long? = null,
        val count: Int = 0,
        val chain: List<Pair<String, String?>> = emptyList(),
        val lookalikes: List<Int> = emptyList(),
    )

    /** A species with its look-alikes resolved, for the species page (§17.4). */
    class Profile(val taxon: Taxon, val lookalikes: List<Taxon>)

    /** One candidate. [genus] = only the genus is claimed, as "a kind of X". */
    data class Guess(val taxon: Taxon, val prob: Float, val genus: Boolean) {
        val name: String get() = if (genus) taxon.genusZh ?: taxon.genusLatin else taxon.zh ?: taxon.latin
        val latin: String get() = if (genus) taxon.genusLatin else taxon.latin
    }

    /** A part of the upright photo, in fractions of its width and height (0–1). */
    data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * [match] is the best photo-to-species similarity; low means "probably
     * not an organism". [region] is the part of the photo the answer came from.
     */
    data class Result(val top: List<Guess>, val match: Float, val genusProb: Float = 0f, val region: Region? = null) {
        /** How sure, for choosing between views: the stronger of species and genus, if it looks alive at all. */
        val sureness: Float
            get() = if (match < MIN_MATCH) 0f else maxOf(top.firstOrNull { !it.genus }?.prob ?: 0f, genusProb)

        /** What Today may suggest unasked: a sure species, else a sure genus, else nothing. */
        val confident: Guess?
            get() {
                if (match < MIN_MATCH) return null
                if (top.firstOrNull()?.taxon?.latin in FOOD) return null
                top.firstOrNull { !it.genus && it.prob >= MIN_SPECIES_PROB }?.let { return it }
                return top.firstOrNull { it.genus && it.prob >= MIN_GENUS_PROB }
            }
        /** Looser than [MIN_MATCH]: only photos clearly not of a living thing (food, streets, skies scored 0.56–0.66) get the "doesn't look like" note. */
        val looksLikeOrganism: Boolean get() = match >= MIN_LIFE
    }

    fun dir(context: Context): File? = context.getExternalFilesDir("species")

    fun isReady(context: Context): Boolean = hasFiles(context) && backend(context) != null

    private fun hasFiles(context: Context): Boolean = dir(context)?.let { d -> REQUIRED.all { File(d, it).isFile } } == true

    /** Where the encoder runs: the NPU whenever it works; the CPU only if switched on; null = not at all. */
    enum class Backend { NPU, CPU }

    fun backend(context: Context): Backend? = when {
        hasNpu(context) -> Backend.NPU
        useCpu(context) -> Backend.CPU
        else -> null
    }

    /** The files are here but the NPU can't be used: the CPU switch is offered (§17.1). */
    fun cpuOffered(context: Context): Boolean = hasFiles(context) && !hasNpu(context)

    fun useCpu(context: Context): Boolean = prefs(context).getBoolean(KEY_USE_CPU, false)

    /** Switch the CPU fallback on or off; only matters where [cpuOffered]. */
    fun setUseCpu(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_CPU, on).apply()
        scope.launch { closeSession() }
    }

    /**
     * A Snapdragon on Android 12+ whose NPU hasn't failed us in this app
     * version. Only QNN drivers for the 8 Gen 2 (V73) and later are packaged;
     * older chips fail the first compile and are written off here.
     */
    fun hasNpu(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && Build.SOC_MANUFACTURER.equals("QTI", ignoreCase = true) &&
            prefs(context).getLong(KEY_NPU_BROKEN, -1L) != appVersion(context)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun appVersion(@Suppress("UNUSED_PARAMETER") context: Context): Long = com.openminis.app.BuildConfig.VERSION_CODE.toLong()

    /**
     * Identifies what the model's verdicts were made with — how photos are
     * looked at, and which species list. Kept verdicts made with anything
     * else are stale and looked at again (no hand-bumped number to forget).
     */
    fun verdictVersion(context: Context): String {
        val d = dir(context) ?: return "none"
        val tsv = File(d, NAMES)
        val bin = File(d, EMBEDDINGS)
        val prior = File(d, PRIOR)
        // CPU verdicts look at less of each photo: redone if the NPU is used later.
        val how = if (backend(context) == Backend.CPU) "$VIEW_STRATEGY/cpu" else VIEW_STRATEGY
        return "$how-${tsv.length()}-${tsv.lastModified()}-${bin.length()}-${bin.lastModified()}-${prior.length()}"
    }

    /** Give up on the NPU for this app version: the feature disappears; an update tries again. */
    private fun markBroken(context: Context, why: String) {
        AppLogger.warning(TAG, "NPU unusable, species ID off: $why")
        prefs(context).edit().putLong(KEY_NPU_BROKEN, appVersion(context)).apply()
    }

    // One encoder run at a time; ONNX Runtime sessions are heavy and the NPU is shared.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + worker)

    private var session: OrtSession? = null
    private var sessionBackend: Backend? = null
    private var table: Table? = null
    private var releaseJob: Job? = null

    /**
     * Identify one photo; null if the files are missing or the photo can't be
     * read. With [region] (drawn by the user) only that part is looked at.
     * Otherwise the middle of the frame, then overlapping parts of it
     * ([Photo.grid]); a part wins over the middle only when clearly surer — a
     * plant on a shelf, not the wall around it. (ML Kit's object detector was
     * tried first: trained on products, its boxes in leaves and branches were
     * unreliable.)
     *
     * Each part is cut from the photo at full resolution (a small spider keeps
     * its legs); the middle and the winner are also looked at mirrored, the
     * two averaged. The
     * answer is weighted by where and when it was taken ([takenAtMs]; place
     * from the photo's GPS, else where Today last was): kinds never recorded
     * in that province, or out of season, count for less.
     */
    suspend fun identify(context: Context, uri: Uri, k: Int = 3, region: Region? = null, takenAtMs: Long? = null): Result? = withContext(worker) {
        if (!isReady(context)) return@withContext null
        val app = context.applicationContext
        // A photo that can't be read is just "no answer" — never a reason to give up on the NPU.
        val photo = try { Photo.open(app, uri) } catch (e: Throwable) {
            AppLogger.warning(TAG, "can't read photo: ${e.message}")
            null
        } ?: return@withContext null
        try {
            val t = try { table ?: loadTable(app).also { table = it } } catch (e: Exception) {
                AppLogger.warning(TAG, "species list unreadable: ${e.message}")
                return@withContext null
            }
            val where = SeenAt(provinceOf(app, photo.latLng ?: com.openminis.app.notification.WeatherAlerts.savedLocation(app)), monthOf(takenAtMs))
            // As it is and mirrored: one look can hinge on which way the leaf points.
            fun look(v: Region, mirrored: Boolean): Result? {
                val px = try { photo.pixels(app, v) } catch (e: Throwable) { return null }
                val a = encode(app, px) ?: throw NpuFailed()
                val emb = if (mirrored) normalised(a, encode(app, mirror(px)) ?: throw NpuFailed()) else a
                return t.rank(emb, k, where).copy(region = v)
            }
            try {
                if (region != null) return@withContext look(photo.square(region), mirrored = true)
                val centre = look(photo.centre(), mirrored = true) ?: return@withContext null
                // On the CPU each look takes about a second: the middle only (a drawn box still reaches the rest).
                if (sessionBackend == Backend.CPU) return@withContext centre
                // The model searches the frame itself: overlapping parts at two sizes, the surest wins
                // over the middle only by a clear margin (Mac test: fixes off-centre subjects, no new
                // false finds on everyday photos). Each part is looked at once; the winner again mirrored.
                val best = photo.grid().mapNotNull { look(it, mirrored = false) }.maxByOrNull { it.sureness }
                if (best == null || best.sureness <= centre.sureness + SWITCH_MARGIN) centre
                else look(best.region!!, mirrored = true)?.takeIf { it.sureness > centre.sureness + SWITCH_MARGIN } ?: centre
            } catch (e: NpuFailed) {
                null
            }
        } finally {
            photo.preview.recycle()
        }
    }

    private class NpuFailed : Exception()

    /** The sum of two embeddings, back to unit length. */
    private fun normalised(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(a.size) { a[it] + b[it] }
        val n = kotlin.math.sqrt(out.fold(0f) { acc, v -> acc + v * v }).coerceAtLeast(1e-6f)
        for (i in out.indices) out[i] /= n
        return out
    }

    /** CHW pixels mirrored left to right. */
    private fun mirror(px: FloatArray): FloatArray {
        val out = FloatArray(px.size)
        for (c in 0 until 3) for (y in 0 until SIZE) {
            val row = c * SIZE * SIZE + y * SIZE
            for (x in 0 until SIZE) out[row + x] = px[row + SIZE - 1 - x]
        }
        return out
    }

    private fun closeSession() {
        runCatching { session?.close() }
        session = null
        sessionBackend = null
    }

    /** One run of the encoder; null when it failed (on the NPU counted, see [MAX_FAILURES]). */
    private fun encode(app: Context, pixels: FloatArray): FloatArray? = try {
        val b = backend(app) ?: throw ai.onnxruntime.OrtException("species ID is off")
        if (sessionBackend != b) closeSession()
        val s = session ?: open(app, b).also { session = it; sessionBackend = b }
        val emb = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(pixels), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { x ->
            s.run(mapOf(s.inputNames.first() to x)).use { out ->
                @Suppress("UNCHECKED_CAST")
                (out[0].value as Array<FloatArray>)[0]
            }
        }
        scheduleRelease()
        if (prefs(app).getInt(KEY_FAILURES, 0) != 0) prefs(app).edit().putInt(KEY_FAILURES, 0).apply()
        emb
    } catch (e: ai.onnxruntime.OrtException) {
        // One failed run may be a hiccup: drop the session (and its compiled cache) and
        // try fresh next time; only several in a row mean this NPU can't do it.
        AppLogger.warning(TAG, "encoder failed: ${e.message}")
        val onNpu = sessionBackend != Backend.CPU
        closeSession()
        if (onNpu) countNpuFailure(app, e)
        null
    }

    private fun countNpuFailure(app: Context, e: Exception) {
        clearCompiled(app)
        val n = prefs(app).getInt(KEY_FAILURES, 0) + 1
        prefs(app).edit().putInt(KEY_FAILURES, n).apply()
        if (n >= MAX_FAILURES) markBroken(app, "$n failed runs: ${e.message}")
    }

    // ── Where and when (the prior) ───────────────────────────────────────

    /** Where and when a photo was taken: a column of [PROVINCES], a month 1–12; null = unknown. */
    class SeenAt(val province: Int?, val month: Int?)

    /**
     * The provinces of species_prior.bin, in its column order: Chinese and
     * English names as Android's Geocoder may give them (adminArea, or the
     * country for Hong Kong, Macau and Taiwan).
     */
    private val PROVINCES = listOf(
        "安徽" to "Anhui", "北京" to "Beijing", "重庆" to "Chongqing", "福建" to "Fujian", "甘肃" to "Gansu", "广东" to "Guangdong",
        "广西" to "Guangxi", "贵州" to "Guizhou", "海南" to "Hainan", "河北" to "Hebei", "黑龙江" to "Heilongjiang", "河南" to "Henan",
        "香港" to "Hong Kong", "湖北" to "Hubei", "湖南" to "Hunan", "内蒙古" to "Inner Mongolia", "江苏" to "Jiangsu", "江西" to "Jiangxi",
        "吉林" to "Jilin", "辽宁" to "Liaoning", "澳门" to "Macau", "宁夏" to "Ningxia", "青海" to "Qinghai", "陕西" to "Shaanxi",
        "山东" to "Shandong", "上海" to "Shanghai", "山西" to "Shanxi", "四川" to "Sichuan", "台湾" to "Taiwan", "天津" to "Tianjin",
        "新疆" to "Xinjiang", "西藏" to "Tibet", "云南" to "Yunnan", "浙江" to "Zhejiang",
    )

    private val provinceCache = HashMap<Pair<Int, Int>, Int?>()

    /** The province a position is in, by reverse geocoding (cached per ~10 km); null outside China or without a Geocoder. */
    private fun provinceOf(context: Context, ll: Pair<Double, Double>?): Int? {
        ll ?: return null
        val key = (ll.first * 10).roundToInt() to (ll.second * 10).roundToInt()
        provinceCache[key]?.let { return it }
        if (provinceCache.containsKey(key) || !android.location.Geocoder.isPresent()) return null
        val found = runCatching {
            @Suppress("DEPRECATION")
            val a = android.location.Geocoder(context, java.util.Locale.CHINA).getFromLocation(ll.first, ll.second, 1)?.firstOrNull()
            val names = listOfNotNull(a?.adminArea, a?.countryName)
            PROVINCES.indexOfFirst { (zh, en) -> names.any { n -> n.startsWith(zh) || n.contains(en, ignoreCase = true) } }.takeIf { it >= 0 }
        }.getOrNull()
        provinceCache[key] = found
        return found
    }

    private fun monthOf(ms: Long?): Int =
        java.time.Instant.ofEpochMilli(ms ?: System.currentTimeMillis()).atZone(java.time.ZoneId.systemDefault()).monthValue

    // ── Where to look ────────────────────────────────────────────────────

    /**
     * A photo being identified: an upright preview (short side ~1024), and
     * the original file for cutting parts at full resolution.
     * Regions are in fractions of the upright photo, so both agree.
     */
    private class Photo(
        val uri: Uri,
        val preview: Bitmap,
        /** EXIF orientation of the original pixels. */
        val orientation: Int,
        val rawW: Int,
        val rawH: Int,
        val latLng: Pair<Double, Double>?,
    ) {
        private val turned = orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
        /** Upright size of the original, in pixels. */
        val width: Float = (if (turned) rawH else rawW).toFloat()
        val height: Float = (if (turned) rawW else rawH).toFloat()

        /**
         * Squares the model looks at besides the middle: a 3×3 of 60 % of
         * the short side and a 2×2 of 75 %, overlapping, so a subject
         * anywhere is near the middle of one of them.
         */
        fun grid(): List<Region> {
            val out = ArrayList<Region>()
            for ((frac, steps) in listOf(0.6f to 3, 0.75f to 2)) {
                val side = minOf(width, height) * frac
                for (i in 0 until steps) for (j in 0 until steps) {
                    val x = (width - side) * i / (steps - 1)
                    val y = (height - side) * j / (steps - 1)
                    out += Region(x / width, y / height, (x + side) / width, (y + side) / height)
                }
            }
            return out
        }

        /** The largest centred square. */
        fun centre(): Region {
            val s = minOf(width, height)
            return Region((width - s) / 2 / width, (height - s) / 2 / height, (width + s) / 2 / width, (height + s) / 2 / height)
        }

        /** [r] grown by [grow] and made square (at least 224 original pixels, at most the short side), kept inside the photo. */
        fun square(r: Region, grow: Float = 1f): Region {
            val w = width
            val h = height
            val cx = (r.left + r.right) / 2 * w
            val cy = (r.top + r.bottom) / 2 * h
            val side = (maxOf((r.right - r.left) * w, (r.bottom - r.top) * h) * grow).coerceAtLeast(minOf(SIZE.toFloat(), minOf(w, h))).coerceAtMost(minOf(w, h))
            val x = (cx - side / 2).coerceIn(0f, w - side)
            val y = (cy - side / 2).coerceIn(0f, h - side)
            return Region(x / w, y / h, (x + side) / w, (y + side) / h)
        }

        /** That part at 224×224, normalised like CLIP (CHW floats): from the original when it can be, else the preview. */
        fun pixels(context: Context, r: Region): FloatArray {
            val part = runCatching { fullRes(context, r) }.onFailure { AppLogger.warning(TAG, "full-res crop failed: ${it.message}") }.getOrNull()
                ?: crop(preview, r)
            val scaled = Bitmap.createScaledBitmap(part, SIZE, SIZE, true)
            if (scaled !== part) part.recycle()
            return floats(scaled).also { scaled.recycle() }
        }

        /** Decodes only [r] of the original — enough pixels for 224 with room to spare — and turns it upright. */
        private fun fullRes(context: Context, r: Region): Bitmap? {
            val (raw, degrees) = when (orientation) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL, androidx.exifinterface.media.ExifInterface.ORIENTATION_UNDEFINED -> r to 0f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> Region(r.top, 1 - r.right, r.bottom, 1 - r.left) to 90f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> Region(1 - r.right, 1 - r.bottom, 1 - r.left, 1 - r.top) to 180f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> Region(1 - r.bottom, r.left, 1 - r.top, r.right) to 270f
                else -> return null // mirrored originals are rare; the preview will do
            }
            val rect = android.graphics.Rect(
                (raw.left * rawW).roundToInt().coerceIn(0, rawW - 1), (raw.top * rawH).roundToInt().coerceIn(0, rawH - 1),
                (raw.right * rawW).roundToInt().coerceIn(1, rawW), (raw.bottom * rawH).roundToInt().coerceIn(1, rawH),
            )
            if (rect.width() < 2 || rect.height() < 2) return null
            var sample = 1
            while (maxOf(rect.width(), rect.height()) / (sample * 2) >= SIZE * 2) sample *= 2
            val bm = context.contentResolver.openInputStream(uri)?.use { input ->
                @Suppress("DEPRECATION")
                val dec = if (Build.VERSION.SDK_INT >= 31) android.graphics.BitmapRegionDecoder.newInstance(input) else android.graphics.BitmapRegionDecoder.newInstance(input, false)
                try { dec?.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample }) } finally { dec?.recycle() }
            } ?: return null
            if (degrees == 0f) return bm
            val up = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, android.graphics.Matrix().apply { postRotate(degrees) }, true)
            if (up !== bm) bm.recycle()
            return up
        }

        companion object {
            fun open(context: Context, uri: Uri): Photo? {
                val preview = decode(context, uri) ?: return null
                // The original (with GPS) needs ACCESS_MEDIA_LOCATION on Android 10+; without it, no place from the photo.
                val exif = runCatching {
                    val src = if (Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.setRequireOriginal(uri) else uri
                    context.contentResolver.openInputStream(src)?.use { androidx.exifinterface.media.ExifInterface(it) }
                }.getOrNull() ?: runCatching { context.contentResolver.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it) } }.getOrNull()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val orientation = exif?.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                    ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                val (rw, rh) = if (bounds.outWidth > 0) bounds.outWidth to bounds.outHeight else preview.width to preview.height
                return Photo(uri, preview, orientation, rw, rh, exif?.latLong?.let { it[0] to it[1] })
            }
        }
    }

    private fun crop(bmp: Bitmap, r: Region): Bitmap {
        val x = (r.left * bmp.width).roundToInt().coerceIn(0, bmp.width - 1)
        val y = (r.top * bmp.height).roundToInt().coerceIn(0, bmp.height - 1)
        val cw = ((r.right - r.left) * bmp.width).roundToInt().coerceIn(1, bmp.width - x)
        val ch = ((r.bottom - r.top) * bmp.height).roundToInt().coerceIn(1, bmp.height - y)
        return Bitmap.createBitmap(bmp, x, y, cw, ch).let { if (it === bmp) bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false) else it }
    }

    private fun floats(scaled: Bitmap): FloatArray {
        val px = IntArray(SIZE * SIZE)
        scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val out = FloatArray(3 * SIZE * SIZE)
        val plane = SIZE * SIZE
        for (i in px.indices) {
            val p = px[i]
            out[i] = (((p shr 16) and 255) / 255f - MEAN[0]) / STD[0]
            out[plane + i] = (((p shr 8) and 255) / 255f - MEAN[1]) / STD[1]
            out[2 * plane + i] = ((p and 255) / 255f - MEAN[2]) / STD[2]
        }
        return out
    }

    /** The species list, parsed once and shared by naming and ranking: by row, by latin, and genus → Chinese. */
    private class Taxa(val list: List<Taxon>) {
        val byLatin = list.associateBy { it.latin }
        val genusZh: Map<String, String> = buildMap { for (t in list) t.genusZh?.let { putIfAbsent(t.genusLatin, it) } }
    }

    private var taxaCache: Taxa? = null

    private fun taxa(context: Context): Taxa? = taxaCache
        ?: dir(context)?.let { File(it, NAMES) }?.takeIf { it.isFile }?.let { Taxa(Table.readTaxa(it)) }?.also { taxaCache = it }

    /** Group and genus for a recorded species, from the names file alone (no model needed). */
    suspend fun taxon(context: Context, latin: String): Taxon? = withContext(Dispatchers.IO) { taxa(context)?.byLatin?.get(latin) }

    /** Several at once (one hop off the main thread). */
    suspend fun taxa(context: Context, latins: Collection<String>): Map<String, Taxon> = withContext(Dispatchers.IO) {
        val t = taxa(context) ?: return@withContext emptyMap()
        latins.mapNotNull { l -> t.byLatin[l]?.let { l to it } }.toMap()
    }

    /**
     * The name to show for [latin], from the current list: a species' own
     * Chinese name, else its genus' (a genus tag "Begonia" reads 秋海棠属).
     * Names are keyed by the scientific name and always come from here, so a
     * better list renames everything at once; a name stored with a tag is only
     * the fallback when the list has none.
     */
    suspend fun names(context: Context, latins: Collection<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val t = taxa(context) ?: return@withContext emptyMap()
        latins.mapNotNull { l -> (t.byLatin[l]?.zh ?: t.genusZh[l])?.let { l to it } }.toMap()
    }

    /** Some species of [genus], for what a genus shares (group, ranks above). */
    suspend fun anyOfGenus(context: Context, genus: String): Taxon? = withContext(Dispatchers.IO) {
        taxa(context)?.list?.firstOrNull { it.genusLatin == genus }
    }

    /** [taxon] plus the species it is easiest to mistake for — offline, from the list itself. */
    suspend fun profile(context: Context, latin: String): Profile? = withContext(Dispatchers.IO) {
        val t = taxa(context) ?: return@withContext null
        val me = t.byLatin[latin] ?: return@withContext null
        Profile(me, me.lookalikes.mapNotNull { t.list.getOrNull(it) })
    }

    /**
     * When [latin] is about, from species_prior.bin: each month's share of its
     * sightings (of the busiest, 0–255, lightly smoothed), January first;
     * null without month data. Just its row, without loading the rest.
     */
    suspend fun months(context: Context, latin: String): List<Int>? = withContext(Dispatchers.IO) {
        val t = taxa(context) ?: return@withContext null
        val row = t.byLatin[latin]?.let { t.list.indexOf(it) }?.takeIf { it >= 0 } ?: return@withContext null
        val f = File(dir(context) ?: return@withContext null, PRIOR).takeIf { it.isFile } ?: return@withContext null
        runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val h = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                raf.readFully(h.array())
                check(h.int == 0x31495250) { "bad species_prior.bin" } // "PRI1"
                val n = h.int
                check(n == t.list.size) { "species_prior.bin does not match species.tsv" }
                raf.seek(12L + n.toLong() * h.int + row * 12L)
                ByteArray(12).also { raf.readFully(it) }.map { it.toInt() and 0xFF }.takeIf { m -> m.any { it > 0 } }
            }
        }.onFailure { AppLogger.warning(TAG, "months: ${it.message}") }.getOrNull()
    }

    /**
     * Load the encoder ahead of time, so the one-off NPU compile happens
     * before anyone waits on it. Once compiled there is nothing to gain: the
     * cache loads in a fraction of a second when a photo needs it, so the NPU
     * and its memory are not taken on every visit to Today.
     */
    fun warmUp(context: Context) {
        if (!isReady(context)) return
        val app = context.applicationContext
        if (backend(app) == Backend.NPU && compiledContext(app).isFile) return
        scope.launch {
            runCatching {
                // Only the NPU is worth loading ahead (its compile); on the CPU the ~0.5 GB session waits for a photo.
                if (session == null && backend(app) == Backend.NPU) { session = open(app, Backend.NPU); sessionBackend = Backend.NPU }
                if (table == null) table = loadTable(app)
            }
                .onFailure { AppLogger.warning(TAG, "warm-up failed: ${it.message}") }
            scheduleRelease()
        }
    }

    private fun loadTable(app: Context): Table = Table.load(dir(app)!!, taxa(app)?.list ?: error("no species.tsv"))

    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            closeSession()
            table = null
        }
    }

    private fun npuCacheRoot(context: Context) = File(context.noBackupFilesDir, "species-npu")

    /** Where the compiled encoder lives — tied to this model file: a new model gets a fresh compile. */
    private fun compiledContext(context: Context): File {
        val model = File(dir(context)!!, NPU_MODEL)
        return File(File(npuCacheRoot(context), "${model.length()}-${model.lastModified()}"), "ctx.onnx")
    }

    private fun clearCompiled(context: Context) = npuCacheRoot(context).deleteRecursively()

    /**
     * The encoder on the NPU: from the compiled cache when there is one,
     * else compile (once, ~35 s) and keep the result. A cache that won't load
     * any more is thrown away and recompiled; a compile that fails means this
     * phone's NPU can't run the model, and the feature is switched off.
     */
    private fun open(context: Context, backend: Backend): OrtSession {
        // No usage reports to Microsoft (the AAR's provider that would send them is removed from the manifest too).
        val env = OrtEnvironment.getEnvironment().also { runCatching { it.setTelemetry(false) } }
        val d = dir(context)!!
        // The CPU reads the same model file as is; nothing to compile or cache.
        if (backend == Backend.CPU) return env.createSession(File(d, NPU_MODEL).path, cpuOptions())
        // The DSP side loads its half of QNN from ADSP_LIBRARY_PATH, so the app's own copy must be on it.
        val libDir = context.applicationInfo.nativeLibraryDir
        android.system.Os.setenv("ADSP_LIBRARY_PATH", "$libDir;/odm/lib/rfsa/adsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/vendor/dsp/cdsp;/dsp", true)
        val model = File(d, NPU_MODEL)
        val ctx = compiledContext(context)
        val cacheDir = ctx.parentFile!!
        if (ctx.isFile) {
            try {
                return env.createSession(ctx.path, npuOptions(compileTo = null))
            } catch (e: ai.onnxruntime.OrtException) {
                AppLogger.warning(TAG, "compiled cache won't load, recompiling: ${e.message}")
            }
        }
        clearCompiled(context)
        cacheDir.mkdirs()
        try {
            return env.createSession(model.path, npuOptions(compileTo = ctx))
        } catch (e: ai.onnxruntime.OrtException) {
            clearCompiled(context)
            markBroken(context, "compile failed: ${e.message}")
            throw e
        }
    }

    private fun cpuOptions() = OrtSession.SessionOptions().apply {
        setCPUArenaAllocator(false)
        setMemoryPatternOptimization(false)
        setIntraOpNumThreads(4)
    }

    private fun npuOptions(compileTo: File?) = OrtSession.SessionOptions().apply {
        setCPUArenaAllocator(false)
        setMemoryPatternOptimization(false)
        if (compileTo != null) {
            addConfigEntry("ep.context_enable", "1")
            addConfigEntry("ep.context_file_path", compileTo.path)
            addConfigEntry("ep.context_embed_mode", "0")
        }
        addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst"))
    }

    private fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder applies the EXIF rotation, so a portrait shot is not seen sideways.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { dec, info, _ ->
                val short = minOf(info.size.width, info.size.height)
                // Big enough that a small subject still has detail once cropped.
                dec.setTargetSampleSize((short / DECODE_SHORT).coerceAtLeast(1))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val short = minOf(bounds.outWidth, bounds.outHeight)
            val opts = BitmapFactory.Options().apply { inSampleSize = (short / DECODE_SHORT).coerceAtLeast(1) }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    }.onFailure { AppLogger.warning(TAG, "decode failed: ${it.message}") }.getOrNull()

    private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    private const val PREFS = "species_id"
    /** App version code in which the NPU was given up on. */
    private const val KEY_NPU_BROKEN = "npuBrokenInVersion"
    /** The user switched on species ID on the CPU (only offered without a usable NPU). */
    private const val KEY_USE_CPU = "useCpu"
    private const val KEY_FAILURES = "npuFailures"

    /**
     * The species list: `species.bin` holds one int8 embedding per species
     * with a per-row scale (`SPC1`, n, dim, float[n] scales, int8[n×dim]);
     * `species.tsv` has, row for row, latin, Chinese name, genus, genus
     * Chinese name and group (iNaturalist's iconic taxon).
     */
    private class Table(
        val taxa: List<Taxon>,
        val scales: FloatArray,
        val emb: ByteArray,
        val genusOf: IntArray,
        val genusRep: IntArray,
        /** [Prior] from species_prior.bin; null when the file isn't there (no weighting). */
        val prior: Prior?,
    ) {
        /**
         * Sightings per species by province (presence) and by month (share of
         * its busiest month, 0–255), from iNaturalist. Turned into gentle
         * weights: a kind never recorded in the province counts ×0.4, one out
         * of season down to ×0.5 — enough to choose between look-alikes, not
         * to overrule a clear match.
         */
        class Prior(val provinces: Int, val geo: ByteArray, val months: ByteArray) {
            fun logWeight(i: Int, at: SeenAt): Float {
                var w = 1f
                at.province?.let { p -> if (p < provinces && geo[i * provinces + p].toInt() == 0) w *= ABSENT_WEIGHT }
                at.month?.let { m ->
                    val share = months[i * 12 + (m - 1)].toInt() and 0xFF
                    // 0 everywhere = no month data for this kind: no weight.
                    if (rowHasMonths(i)) w *= OFF_SEASON_FLOOR + (1 - OFF_SEASON_FLOOR) * share / 255f
                }
                return kotlin.math.ln(w)
            }
            private fun rowHasMonths(i: Int): Boolean { for (m in 0 until 12) if (months[i * 12 + m].toInt() != 0) return true; return false }
        }

        fun rank(q: FloatArray, k: Int, at: SeenAt = SeenAt(null, null)): Result {
            val n = taxa.size
            val logits = FloatArray(n)
            var best = -1f
            for (i in 0 until n) {
                var s = 0f
                val base = i * DIM
                for (j in 0 until DIM) s += q[j] * emb[base + j]
                val cos = s * scales[i]
                if (cos > best) best = cos
                logits[i] = cos
            }
            var sum = 0.0
            val probs = FloatArray(n)
            for (i in 0 until n) {
                val bias = prior?.logWeight(i, at) ?: 0f
                val e = exp(((logits[i] - best) * LOGIT_SCALE + bias).toDouble()); probs[i] = e.toFloat(); sum += e
            }
            val genusProb = FloatArray(genusRep.size)
            // The k best in one pass (k is 1–3): no sort of 36k rows.
            val top = IntArray(k) { -1 }
            for (i in 0 until n) {
                probs[i] = (probs[i] / sum).toFloat()
                genusProb[genusOf[i]] += probs[i]
                var at = k
                while (at > 0 && (top[at - 1] < 0 || probs[i] > probs[top[at - 1]])) at--
                if (at < k) { for (m in k - 1 downTo at + 1) top[m] = top[m - 1]; top[at] = i }
            }
            val guesses = top.filter { it >= 0 }.map { Guess(taxa[it], probs[it], genus = false) }.toMutableList()
            // The genus is offered too when its species split the vote but together are sure.
            var g = 0
            for (i in genusProb.indices) if (genusProb[i] > genusProb[g]) g = i
            val rep = genusRep[g]
            if (genusProb[g] > guesses.first().prob + 0.15f) guesses += Guess(taxa[rep], genusProb[g], genus = true)
            return Result(guesses, best, genusProb[g])
        }

        companion object {
            /** latin, Chinese, genus, genus Chinese, group, iNat id, count, chain ("A=甲>B=乙"), look-alike rows ("1,2,3"). */
            fun readTaxa(f: File): List<Taxon> = f.readLines().filter { it.isNotBlank() }.map { line ->
                val c = line.split('\t')
                Taxon(
                    latin = c[0],
                    zh = c.getOrNull(1)?.ifBlank { null },
                    genusLatin = c.getOrNull(2).orEmpty().ifBlank { c[0].substringBefore(' ') },
                    genusZh = c.getOrNull(3)?.ifBlank { null },
                    group = c.getOrNull(4)?.ifBlank { null },
                    inatId = c.getOrNull(5)?.toLongOrNull(),
                    count = c.getOrNull(6)?.toIntOrNull() ?: 0,
                    chain = c.getOrNull(7).orEmpty().split('>').filter { it.isNotBlank() }.map { r ->
                        r.substringBefore('=') to r.substringAfter('=', "").ifBlank { null }
                    },
                    lookalikes = c.getOrNull(8).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() },
                )
            }

            /** species_prior.bin: "PRI1", n, provinces, then n×provinces presence bytes and n×12 month bytes. */
            fun loadPrior(d: File, n: Int): Prior? = runCatching {
                RandomAccessFile(File(d, PRIOR), "r").use { raf ->
                    val h = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                    raf.readFully(h.array())
                    check(h.int == 0x31495250) { "bad species_prior.bin" } // "PRI1"
                    check(h.int == n) { "species_prior.bin does not match species.tsv" }
                    val p = h.int
                    Prior(p, ByteArray(n * p).also { raf.readFully(it) }, ByteArray(n * 12).also { raf.readFully(it) })
                }
            }.onFailure { if (File(d, PRIOR).exists()) AppLogger.warning(TAG, "prior unreadable: ${it.message}") }.getOrNull()

            fun load(d: File, taxa: List<Taxon>): Table {
                RandomAccessFile(File(d, EMBEDDINGS), "r").use { raf ->
                    val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                    raf.readFully(header.array())
                    check(header.int == 0x31435053) { "bad species.bin" } // "SPC1"
                    val n = header.int
                    check(header.int == DIM && n == taxa.size) { "species.bin does not match species.tsv" }
                    val sb = ByteArray(4 * n).also { raf.readFully(it) }
                    val scales = FloatArray(n).also { ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
                    val emb = ByteArray(n * DIM).also { raf.readFully(it) }
                    val idx = HashMap<String, Int>()
                    val reps = ArrayList<Int>()
                    val genusOf = IntArray(n) { i -> idx.getOrPut(taxa[i].genusLatin) { reps += i; reps.size - 1 } }
                    return Table(taxa, scales, emb, genusOf, reps.toIntArray(), loadPrior(d, n))
                }
            }
        }
    }
}
