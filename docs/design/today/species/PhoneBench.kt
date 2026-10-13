package bench

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Debug
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

// 屏幕锁着也能跑：adb shell am instrument -w com.openminis.bcbench/bench.Bench
// 模型和图片先 adb push 到 /sdcard/Android/data/com.openminis.bcbench/files/
class Bench : Instrumentation() {
    private var only: String? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); only = arguments?.getString("mode"); start() }

    private fun say(s: String) { Log.i("BCBENCH", s); val b = Bundle(); b.putString(REPORT_KEY_STREAMRESULT, s + "\n"); sendStatus(0, b) }

    private fun pss() = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024

    override fun onStart() {
        val dir = context.getExternalFilesDir(null)!!
        val libDir = context.applicationInfo.nativeLibraryDir
        android.system.Os.setenv("ADSP_LIBRARY_PATH", "$libDir;/odm/lib/rfsa/adsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/vendor/dsp/cdsp;/dsp", true)
        say("libDir=$libDir files=${File(libDir).list()?.filter { "Qnn" in it }}")
        val env = OrtEnvironment.getEnvironment()
        val img = preprocess(File(dir, "test.jpg"))
        val runs = listOf("bioclip2_img_int8.onnx" to "cpu4", "bioclip2_img_int8.onnx" to "noprepack", "bioclip2_img_int8.onnx" to "noarena", "bioclip2_img_int8.onnx" to "lean", "bioclip2_img.onnx" to "qnn_fp16", "bioclip2_img_w8a16.onnx" to "qnn_w8a16", "bioclip2_img_w8a16.onnx" to "qnnA", "bioclip2_img_w8a16.onnx" to "qnnB", "bioclip2_img_w8a16.onnx" to "qnnCtx")
        for ((model, mode) in runs.filter { only == null || it.second == only }) {
            System.gc(); Thread.sleep(500); val base = pss()
            try {
                val opts = OrtSession.SessionOptions(); opts.setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO)
                when (mode) {
                    "cpu4" -> opts.setIntraOpNumThreads(4)
                    "noprepack" -> { opts.setIntraOpNumThreads(4); opts.addConfigEntry("session.disable_prepacking", "1") }
                    "noarena" -> { opts.setIntraOpNumThreads(4); opts.setCPUArenaAllocator(false); opts.setMemoryPatternOptimization(false) }
                    "lean" -> { opts.setIntraOpNumThreads(4); opts.addConfigEntry("session.disable_prepacking", "1"); opts.setCPUArenaAllocator(false); opts.setMemoryPatternOptimization(false) }
                    "qnn_w8a16" -> opts.addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst"))
                    "qnnA" -> opts.addQnn(mapOf("backend_path" to "libQnnHtp.so"))
                    "qnnB" -> opts.addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_arch" to "79", "soc_model" to "69", "device_id" to "0"))
                    "qnnCtx" -> {
                        opts.setCPUArenaAllocator(false); opts.setMemoryPatternOptimization(false)
                        if (!File(dir, "ctx.onnx").exists()) { opts.addConfigEntry("ep.context_enable", "1"); opts.addConfigEntry("ep.context_file_path", File(dir, "ctx.onnx").path); opts.addConfigEntry("ep.context_embed_mode", "0") }
                        opts.addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst"))
                    }
                    "qnn_fp16" -> opts.addQnn(mapOf("backend_path" to "libQnnHtp.so", "enable_htp_fp16_precision" to "1", "htp_performance_mode" to "burst"))
                }
                var t = System.nanoTime()
                val s = env.createSession(if (mode == "qnnCtx" && File(dir, "ctx.onnx").exists()) File(dir, "ctx.onnx").path else File(dir, model).path, opts)
                val load = (System.nanoTime() - t) / 1e6
                val x = OnnxTensor.createTensor(env, FloatBuffer.wrap(img), longArrayOf(1, 3, 224, 224))
                t = System.nanoTime(); var emb = (s.run(mapOf("image" to x))[0].value as Array<FloatArray>)[0]
                val first = (System.nanoTime() - t) / 1e6
                val ts = (1..5).map { t = System.nanoTime(); emb = (s.run(mapOf("image" to x))[0].value as Array<FloatArray>)[0]; (System.nanoTime() - t) / 1e6 }
                say("$model $mode load=${load.toInt()}ms first=${first.toInt()}ms runs=${ts.map { it.toInt() }} pss=${pss() - base}MB(+base ${base}) emb=${emb.take(4)}")
                File(dir, "emb_$mode.txt").writeText(emb.joinToString(","))
                s.close()
            } catch (e: Throwable) { say("$model $mode FAILED $e") }
        }
        finish(0, Bundle())
    }

    private fun preprocess(f: File): FloatArray {
        val src = BitmapFactory.decodeFile(f.path)
        val sc = 224f / minOf(src.width, src.height)
        val bm = android.graphics.Bitmap.createScaledBitmap(src, maxOf(224, Math.round(src.width * sc)), maxOf(224, Math.round(src.height * sc)), true)
        val l = (bm.width - 224) / 2; val tp = (bm.height - 224) / 2
        val px = IntArray(224 * 224); bm.getPixels(px, 0, 224, l, tp, 224, 224)
        val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f); val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        val out = FloatArray(3 * 224 * 224)
        for (i in px.indices) {
            val p = px[i]
            out[i] = (((p shr 16) and 255) / 255f - mean[0]) / std[0]
            out[224 * 224 + i] = (((p shr 8) and 255) / 255f - mean[1]) / std[1]
            out[2 * 224 * 224 + i] = ((p and 255) / 255f - mean[2]) / std[2]
        }
        return out
    }
}
