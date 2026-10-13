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
 * Files live in `getExternalFilesDir("species")` (see [REQUIRED]); without
 * them [isReady] is false and Today simply shows no suggestions.
 *
 * Snapdragon phones run the encoder on the Hexagon NPU through ONNX Runtime's
 * QNN provider (~30 ms a photo). The first load compiles the model for the
 * NPU (~35 s) and caches the result; later loads take well under a second.
 * Everything else, or a failed NPU setup, uses the CPU (~0.4 s a photo).
 */
object SpeciesId {
    private const val TAG = "SpeciesId"
    private const val SIZE = 224
    private const val DIM = 768
    private const val LOGIT_SCALE = 100f

    private const val NPU_MODEL = "bioclip2_img_w8a16.onnx"
    private const val CPU_MODEL = "bioclip2_img_int8.onnx"
    private const val EMBEDDINGS = "species.bin"
    private const val NAMES = "species.tsv"
    private val REQUIRED = listOf(CPU_MODEL, EMBEDDINGS, NAMES)

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

    data class Taxon(val latin: String, val zh: String?, val genusLatin: String, val genusZh: String?, val group: String?)

    /** One candidate. [genus] = only the genus is claimed, as "a kind of X". */
    data class Guess(val taxon: Taxon, val prob: Float, val genus: Boolean) {
        val name: String get() = if (genus) taxon.genusZh ?: taxon.genusLatin else taxon.zh ?: taxon.latin
        val latin: String get() = if (genus) taxon.genusLatin else taxon.latin
    }

    /** [match] is the best photo-to-species similarity; low means "probably not an organism". */
    data class Result(val top: List<Guess>, val match: Float) {
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

    fun isReady(context: Context): Boolean = dir(context)?.let { d -> REQUIRED.all { File(d, it).isFile } } == true

    // One encoder run at a time; ONNX Runtime sessions are heavy and the NPU is shared.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + worker)

    private var session: OrtSession? = null
    private var onNpu = false
    private var table: Table? = null
    private var releaseJob: Job? = null

    /** Identify one photo; null if the files are missing or the photo can't be read. */
    suspend fun identify(context: Context, uri: Uri, k: Int = 3): Result? = withContext(worker) {
        if (!isReady(context)) return@withContext null
        val app = context.applicationContext
        // A photo that can't be read or scaled is just "no answer" — never a reason to give up on the NPU.
        val pixels = try { loadPixels(app, uri) } catch (e: Throwable) {
            AppLogger.warning(TAG, "can't prepare photo: ${e.message}")
            null
        } ?: return@withContext null
        val t = try { table ?: Table.load(dir(app)!!).also { table = it } } catch (e: Exception) {
            AppLogger.warning(TAG, "species list unreadable: ${e.message}")
            return@withContext null
        }
        try {
            val s = session ?: open(app).also { session = it }
            val emb = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(pixels), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { x ->
                s.run(mapOf(s.inputNames.first() to x)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    (out[0].value as Array<FloatArray>)[0]
                }
            }
            scheduleRelease()
            t.rank(emb, k)
        } catch (e: ai.onnxruntime.OrtException) {
            AppLogger.warning(TAG, "encoder failed (npu=$onNpu): ${e.message}")
            if (onNpu) {
                // The NPU itself failed: never try it again on this phone; the next call uses the CPU.
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NPU_BROKEN, true).apply()
                runCatching { session?.close() }
                session = null
            }
            null
        }
    }

    private var names: Map<String, Taxon>? = null

    /** Group and genus for a recorded species, from the names file alone (no model needed). */
    suspend fun taxon(context: Context, latin: String): Taxon? = withContext(Dispatchers.IO) {
        val map = names ?: run {
            val f = dir(context)?.let { File(it, NAMES) }?.takeIf { it.isFile } ?: return@withContext null
            Table.readTaxa(f).associateBy { it.latin }.also { names = it }
        }
        map[latin]
    }

    /** Load the encoder ahead of time, so the one-off NPU compile happens before anyone waits on it. */
    fun warmUp(context: Context) {
        if (!isReady(context)) return
        val app = context.applicationContext
        scope.launch {
            runCatching { if (session == null) session = open(app); if (table == null) table = Table.load(dir(app)!!) }
                .onFailure { AppLogger.warning(TAG, "warm-up failed: ${it.message}") }
            scheduleRelease()
        }
    }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            runCatching { session?.close() }
            session = null
            table = null
        }
    }

    private fun open(context: Context): OrtSession {
        val env = OrtEnvironment.getEnvironment()
        val d = dir(context)!!
        if (useNpu(context, d)) {
            try {
                return openNpu(context, env, d).also { onNpu = true }
            } catch (e: Throwable) {
                AppLogger.warning(TAG, "NPU unavailable, using CPU: ${e.message}")
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NPU_BROKEN, true).apply()
            }
        }
        onNpu = false
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            // The arena keeps ~550 MB of scratch around after a run; without it
            // the session costs about the model's own 300 MB, at the same speed.
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        }
        return env.createSession(File(d, CPU_MODEL).path, opts)
    }

    private fun useNpu(context: Context, d: File): Boolean =
        Build.VERSION.SDK_INT >= 31 && Build.SOC_MANUFACTURER.equals("QTI", ignoreCase = true) &&
            File(d, NPU_MODEL).isFile && File(d, "$NPU_MODEL.data").isFile &&
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NPU_BROKEN, false)

    private fun openNpu(context: Context, env: OrtEnvironment, d: File): OrtSession {
        // The DSP side loads its half of QNN from ADSP_LIBRARY_PATH, so the app's own copy must be on it.
        val libDir = context.applicationInfo.nativeLibraryDir
        android.system.Os.setenv("ADSP_LIBRARY_PATH", "$libDir;/odm/lib/rfsa/adsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/vendor/dsp/cdsp;/dsp", true)
        // Compiled-for-NPU cache, tied to this model file: a new model gets a fresh compile.
        val model = File(d, NPU_MODEL)
        val cacheDir = File(context.noBackupFilesDir, "species-npu/${model.length()}-${model.lastModified()}")
        val ctx = File(cacheDir, "ctx.onnx")
        val opts = OrtSession.SessionOptions().apply {
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            if (!ctx.isFile) {
                cacheDir.parentFile?.listFiles()?.forEach { it.deleteRecursively() }
                cacheDir.mkdirs()
                addConfigEntry("ep.context_enable", "1")
                addConfigEntry("ep.context_file_path", ctx.path)
                addConfigEntry("ep.context_embed_mode", "0")
            }
            addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst"))
        }
        return env.createSession(if (ctx.isFile) ctx.path else model.path, opts)
    }

    /** Decode upright, scale the short side to 224, centre-crop, normalise like CLIP. Returns CHW floats. */
    private fun loadPixels(context: Context, uri: Uri): FloatArray? {
        val src = decode(context, uri) ?: return null
        val sc = SIZE.toFloat() / minOf(src.width, src.height)
        val w = maxOf(SIZE, (src.width * sc).roundToInt())
        val h = maxOf(SIZE, (src.height * sc).roundToInt())
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(SIZE * SIZE)
        scaled.getPixels(px, 0, SIZE, (w - SIZE) / 2, (h - SIZE) / 2, SIZE, SIZE)
        if (scaled !== src) scaled.recycle()
        src.recycle()
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

    private fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder applies the EXIF rotation, so a portrait shot is not seen sideways.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { dec, info, _ ->
                val short = minOf(info.size.width, info.size.height)
                dec.setTargetSampleSize((short / (SIZE * 2)).coerceAtLeast(1))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val short = minOf(bounds.outWidth, bounds.outHeight)
            val opts = BitmapFactory.Options().apply { inSampleSize = (short / (SIZE * 2)).coerceAtLeast(1) }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    }.onFailure { AppLogger.warning(TAG, "decode failed: ${it.message}") }.getOrNull()

    private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    private const val PREFS = "species_id"
    private const val KEY_NPU_BROKEN = "npuBroken"

    /**
     * The species list: `species.bin` holds one int8 embedding per species
     * with a per-row scale (`SPC1`, n, dim, float[n] scales, int8[n×dim]);
     * `species.tsv` has, row for row, latin, Chinese name, genus, genus
     * Chinese name and group (iNaturalist's iconic taxon).
     */
    private class Table(val taxa: List<Taxon>, val scales: FloatArray, val emb: ByteArray, val genusOf: IntArray, val genera: List<String>) {
        fun rank(q: FloatArray, k: Int): Result {
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
            for (i in 0 until n) { val e = exp(((logits[i] - best) * LOGIT_SCALE).toDouble()); probs[i] = e.toFloat(); sum += e }
            val genusProb = FloatArray(genera.size)
            for (i in 0 until n) { probs[i] = (probs[i] / sum).toFloat(); genusProb[genusOf[i]] += probs[i] }
            val topSpecies = probs.indices.sortedByDescending { probs[it] }.take(k)
            val guesses = topSpecies.map { Guess(taxa[it], probs[it], genus = false) }.toMutableList()
            // The genus is offered too when its species split the vote but together are sure.
            val g = genusProb.indices.maxBy { genusProb[it] }
            val rep = taxa.indices.first { genusOf[it] == g }
            if (genusProb[g] > guesses.first().prob + 0.15f) guesses += Guess(taxa[rep], genusProb[g], genus = true)
            return Result(guesses, best)
        }

        companion object {
            fun readTaxa(f: File): List<Taxon> = f.readLines().filter { it.isNotBlank() }.map { line ->
                val c = line.split('\t')
                Taxon(c[0], c.getOrNull(1)?.ifBlank { null }, c.getOrNull(2).orEmpty().ifBlank { c[0].substringBefore(' ') }, c.getOrNull(3)?.ifBlank { null }, c.getOrNull(4)?.ifBlank { null })
            }

            fun load(d: File): Table {
                val taxa = readTaxa(File(d, NAMES))
                RandomAccessFile(File(d, EMBEDDINGS), "r").use { raf ->
                    val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                    raf.readFully(header.array())
                    check(header.int == 0x31435053) { "bad species.bin" } // "SPC1"
                    val n = header.int
                    check(header.int == DIM && n == taxa.size) { "species.bin does not match species.tsv" }
                    val sb = ByteArray(4 * n).also { raf.readFully(it) }
                    val scales = FloatArray(n).also { ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
                    val emb = ByteArray(n * DIM).also { raf.readFully(it) }
                    val genera = taxa.map { it.genusLatin }.distinct()
                    val idx = genera.withIndex().associate { it.value to it.index }
                    return Table(taxa, scales, emb, IntArray(n) { idx[taxa[it].genusLatin]!! }, genera)
                }
            }
        }
    }
}
