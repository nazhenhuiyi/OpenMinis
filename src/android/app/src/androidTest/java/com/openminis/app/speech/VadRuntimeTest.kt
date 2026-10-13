package com.openminis.app.speech

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.codeconcept.realtimecutvadlibrary.VADCallback
import io.codeconcept.realtimecutvadlibrary.VADWrapper
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [T-android-vad] The VAD's native library loads against the ONNX Runtime
 * this APK ships (1.29, for species ID — see patchVad in build.gradle.kts)
 * and still hears speech: a spoken sentence (48 kHz mono, macOS `say`)
 * between a second of silence before and eight after must give one start
 * and one end. Before the patch it failed to load at all
 * ("cannot locate symbol OrtGetApiBase").
 */
@RunWith(AndroidJUnit4::class)
class VadRuntimeTest {
    @Test
    fun speechStartsAndEnds() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val speech = ByteBuffer.wrap(ins.context.assets.open("vad_speech_48k.raw").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val audio = FloatArray(48_000) + FloatArray(speech.remaining()) { speech.get() / 32768f } + FloatArray(8 * 48_000)
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        var frame = 0
        val w = VADWrapper(ins.targetContext)
        try {
            w.setVADModel(VADWrapper.SileroModelVersion.V5)
            w.setVADSampleRate(VADWrapper.SampleRate.SAMPLERATE_48)
            w.setVADThreshold(0.5f, 0.5f, 0.5f, 0.95f, 10, 156)
            w.setVADCallback(object : VADCallback {
                override fun onVoiceStart() { starts += frame }
                override fun onVoiceEnd(wav: ByteArray?) { ends += frame }
                override fun onVoiceDidContinue(pcm: ByteArray?) {}
            })
            while ((frame + 1) * 512 <= audio.size) {
                w.processAudio(audio.copyOfRange(frame * 512, (frame + 1) * 512))
                frame++
            }
        } finally {
            w.release()
        }
        val sec = { f: Int -> "%.2fs".format(f * 512 / 48_000f) }
        Log.i("VadRuntimeTest", "starts=${starts.map(sec)} ends=${ends.map(sec)}")
        assertTrue("speech start, at ${starts.map(sec)}", starts.size == 1 && starts[0] * 512 / 48_000f in 0.9f..2f)
        assertTrue("speech end, at ${ends.map(sec)}", ends.size == 1)
    }
}
