package com.openminis.app.data.murmur

import android.content.Context
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

/**
 * [T-today-murmur] The on-device 碎碎念 tagger (SPEC §19.1): Qwen3-0.6B
 * fine-tuned for the tag line in [MurmurLabels], Q4_0, run by llama.cpp on
 * two CPU threads (~0.4 s and ~750 MB on an 8 Elite). Nothing leaves the phone.
 *
 * The model is loaded on first use and released after [IDLE_RELEASE_MS] idle,
 * so the memory is only held while tagging. One call at a time.
 *
 * The file is not in the APK: it lives in `getExternalFilesDir("murmur")`
 * (adb-pushed for the preview, downloaded later — like the species model).
 */
object MurmurLlm {
    private const val TAG = "MurmurLlm"
    const val MODEL_FILE = "murmur-0.6b-q4_0.gguf"
    /** Stored with each tag: a new model file means every murmur is tagged again. */
    const val MODEL_VERSION = "soup5-q4_0"
    private const val THREADS = 2
    private const val N_CTX = 512
    private const val MAX_TOKENS = 80
    private const val IDLE_RELEASE_MS = 60_000L

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private var handle = 0L
    private var releaseJob: Job? = null
    @Volatile private var libLoaded: Boolean? = null

    fun file(context: Context): File? = context.getExternalFilesDir("murmur")?.let { File(it, MODEL_FILE) }

    fun isReady(context: Context): Boolean = file(context)?.isFile == true && loadLib()

    private fun loadLib(): Boolean = libLoaded ?: runCatching { System.loadLibrary("murmur_llm") }
        .onFailure { AppLogger.warning(TAG, "native lib: ${it.message}") }
        .isSuccess.also { libLoaded = it }

    /** Tag one murmur; null if the model is missing or the run failed. */
    suspend fun tag(context: Context, text: String): MurmurTags? = withContext(worker) {
        releaseJob?.cancel()
        try {
            if (handle == 0L) {
                val f = file(context)?.takeIf { it.isFile && loadLib() } ?: return@withContext null
                val t0 = System.currentTimeMillis()
                handle = nativeLoad(f.path, THREADS, N_CTX)
                if (handle == 0L) {
                    AppLogger.warning(TAG, "load failed")
                    return@withContext null
                }
                AppLogger.info(TAG, "loaded in ${System.currentTimeMillis() - t0} ms")
            }
            val res = nativeRun(handle, MurmurLabels.prompt(text), MurmurLabels.GRAMMAR, "记录", MAX_TOKENS) ?: return@withContext null
            val nl = res.indexOf('\n')
            val pRecord = res.substring(0, nl).toFloatOrNull() ?: return@withContext null
            MurmurLabels.parse(res.substring(nl + 1), text.take(MurmurLabels.MAX_CHARS), 1f - pRecord)
        } finally {
            scheduleRelease()
        }
    }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            if (handle != 0L) {
                nativeFree(handle)
                handle = 0L
                AppLogger.info(TAG, "released")
            }
        }
    }

    @JvmStatic private external fun nativeLoad(path: String, threads: Int, nCtx: Int): Long
    @JvmStatic private external fun nativeRun(handle: Long, prompt: String, grammar: String, probe: String, maxTokens: Int): String?
    @JvmStatic private external fun nativeFree(handle: Long)
}
