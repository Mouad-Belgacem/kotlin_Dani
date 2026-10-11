package com.tarteelwarsh.tilawa.fbank

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test dore (execute sur le vrai device, aucun equivalent JVM possible - le
 * calcul passe par le JNI/C++ compile pour ARM) : compare StreamingFbank
 * (port JNI de kaldi_native_fbank.OnlineFbank) a la sortie REELLE de
 * kaldi_native_fbank cote Python, sur 3s d'audio reel
 * (Dani_app/audio.wav, jamais modifie), generee par
 * scripts/generate_fbank_goldens.py avec EXACTEMENT la meme config que
 * fbank_jni.cpp. Alimente aussi StreamingFbank par chunks irreguliers (pas
 * juste en un seul accept_waveform) - le script de generation a deja
 * confirme cote Python un ecart de 0.0 entre calcul one-shot et calcul par
 * chunks ; ce test verifie que c'est egalement vrai pour NOTRE JNI.
 */
@RunWith(AndroidJUnit4::class)
class FbankGoldenDeviceTest {

    private data class Fixture(val samples: FloatArray, val frames: Array<FloatArray>, val featureDim: Int)

    private fun loadFixture(): Fixture {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bytes = context.assets.open("goldens/fbank_golden.bin").use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val numSamples = buf.int
        val samples = FloatArray(numSamples)
        buf.asFloatBuffer().get(samples, 0, numSamples)
        buf.position(buf.position() + numSamples * 4)

        val numFrames = buf.int
        val featureDim = buf.int
        val frames = Array(numFrames) { FloatArray(featureDim) }
        val flat = FloatArray(numFrames * featureDim)
        buf.asFloatBuffer().get(flat, 0, flat.size)
        for (i in 0 until numFrames) {
            System.arraycopy(flat, i * featureDim, frames[i], 0, featureDim)
        }
        return Fixture(samples, frames, featureDim)
    }

    @Test
    fun matchesThePythonReferenceFedInOneShot() {
        val fixture = loadFixture()

        StreamingFbank().use { fbank ->
            assertEquals(fixture.featureDim, fbank.featureDim)

            fbank.acceptWaveform(16000f, fixture.samples)
            fbank.inputFinished()

            assertEquals(fixture.frames.size, fbank.numFramesReady)

            var maxDiff = 0f
            for (i in fixture.frames.indices) {
                val expected = fixture.frames[i]
                val actual = fbank.getFrame(i)
                assertEquals(fixture.featureDim, actual.size)
                for (d in 0 until fixture.featureDim) {
                    maxDiff = maxOf(maxDiff, kotlin.math.abs(expected[d] - actual[d]))
                }
            }
            android.util.Log.i("FbankGoldenDeviceTest", "one-shot: maxDiff vs Python reference = $maxDiff")
            // Tolerance large pour d'eventuelles differences d'arrondi
            // float32 entre plateformes (meme bibliotheque C++, mais
            // compilateurs/optimisations differents entre le wheel Python
            // Windows x86_64 et le binaire ARM du NDK) - une vraie
            // divergence de config donnerait un ecart de plusieurs ordres
            // de grandeur au-dessus de ca, pas un ecart d'arrondi.
            assertTrue("ecart trop grand vs reference Python (config divergente ?): $maxDiff", maxDiff < 1e-2f)
        }
    }

    @Test
    fun matchesThePythonReferenceFedInIrregularChunks() {
        val fixture = loadFixture()
        val chunkSizes = intArrayOf(1600, 733, 2001, 512, 1337) // memes tailles que cote Python, volontairement non alignees sur 160 (10ms)

        StreamingFbank().use { fbank ->
            var idx = 0
            var chunkIdx = 0
            while (idx < fixture.samples.size) {
                val size = minOf(chunkSizes[chunkIdx % chunkSizes.size], fixture.samples.size - idx)
                val chunk = fixture.samples.copyOfRange(idx, idx + size)
                fbank.acceptWaveform(16000f, chunk)
                idx += size
                chunkIdx++
            }
            fbank.inputFinished()

            assertEquals(fixture.frames.size, fbank.numFramesReady)

            var maxDiff = 0f
            for (i in fixture.frames.indices) {
                val expected = fixture.frames[i]
                val actual = fbank.getFrame(i)
                for (d in 0 until fixture.featureDim) {
                    maxDiff = maxOf(maxDiff, kotlin.math.abs(expected[d] - actual[d]))
                }
            }
            android.util.Log.i("FbankGoldenDeviceTest", "chunked: maxDiff vs Python reference = $maxDiff")
            assertTrue("ecart trop grand vs reference Python en mode chunke: $maxDiff", maxDiff < 1e-2f)
        }
    }
}
