package com.tarteelwarsh.tilawa.asr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonArray
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonInt
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject
import com.tarteelwarsh.tilawa.logic.tokenizer.RunAwareTokenizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Valide `StreamingZipformerEncoder` (le pilote VRAIMENT incremental, sans
 * padding de queue, utilise par core-pipeline) contre la meme reference
 * Python batch que AsrGoldenDeviceTest (asr_golden_meta.json).
 *
 * Difference de contrat assumee (voir StreamingZipformerEncoder, docstring) :
 * la reference batch complete son tout dernier chunk avec du padding
 * LOG_EPS ; ce pilote live ne le fait jamais, donc n'atteint la fenetre qui
 * en depend que si assez d'audio REEL suit. Avec seulement les 5s du
 * golden, 7 fenetres completes sont atteignables sans aucun padding
 * (pointeurs 0,64,...,384 ; la 8e a besoin de frames au-dela de la frame
 * 500) - la somme de `numNewTokens` sur ces 7 chunks (0+3+5+4+5+3+2=22)
 * donne le prefixe exact attendu. Ce test verifie que ce prefixe est
 * ATTEINT A L'IDENTIQUE (egalite stricte, pas de tolerance), en alimentant
 * par chunks de 1600 echantillons - le grain reel de core-audio, pas un
 * unique appel batch.
 */
@RunWith(AndroidJUnit4::class)
class StreamingEncoderGoldenDeviceTest {

    private fun materializeModel(): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        val dest = File(context.cacheDir, "zipformer_warsh_standard_streaming.onnx")
        val assetSize = try {
            context.assets.openFd("model/zipformer_warsh_standard_streaming.onnx").use { it.length }
        } catch (e: java.io.IOException) {
            -1L
        }
        if (assetSize < 0 || !dest.exists() || dest.length() != assetSize) {
            context.assets.open("model/zipformer_warsh_standard_streaming.onnx").use { input ->
                dest.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
            }
        }
        return dest
    }

    @Test
    fun incrementalFeedingMatchesAnExactPrefixOfTheBatchReference() {
        val context = InstrumentationRegistry.getInstrumentation().context

        val audioBytes = context.assets.open("goldens/audio_golden.bin").use { it.readBytes() }
        val audioBuf = ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN)
        val numSamples = audioBuf.int
        val samples = FloatArray(numSamples)
        audioBuf.asFloatBuffer().get(samples, 0, numSamples)

        val metaText = context.assets.open("goldens/asr_golden_meta.json").use {
            it.bufferedReader(Charsets.UTF_8).readText()
        }
        val goldenRoot = JsonParser.parse(metaText).asJsonObject()
        val goldenTokenIds = goldenRoot.getValue("tokenIds").asJsonArray().map { it.asJsonInt() }

        val expectedPrefixLen = 22 // cf. docstring : 7 fenetres completes sans padding sur ces 5s
        val expectedPrefix = goldenTokenIds.subList(0, expectedPrefixLen).toIntArray()

        val meta = context.assets.open("model/streaming_meta.json").use { StreamingMeta.parse(it) }
        val modelFile = materializeModel()
        val env = OrtEnvironment.getEnvironment()
        val ortSession = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
        val tokenizer = RunAwareTokenizer.fromResource()

        StreamingZipformerEncoder.create(env, ortSession, meta, tokenizer).use { encoder ->
            val chunkSamples = 1600 // 100ms a 16kHz, meme grain que core-audio.AudioConfig
            var idx = 0
            while (idx < samples.size) {
                val end = minOf(idx + chunkSamples, samples.size)
                encoder.pushAudio(samples.copyOfRange(idx, end))
                idx = end
            }

            val actualTokenIds = encoder.tokenIds()
            android.util.Log.i(
                "StreamingEncoderGoldenDeviceTest",
                "tokenIds accumules=${actualTokenIds.size} (attendu prefixe=$expectedPrefixLen, total golden=${goldenTokenIds.size})",
            )

            assertTrue(
                "le pilote incremental doit atteindre au moins le prefixe attendu (${actualTokenIds.size} < $expectedPrefixLen)",
                actualTokenIds.size >= expectedPrefixLen,
            )
            assertArrayEquals(
                "prefixe des ids CTC different de la reference Python batch",
                expectedPrefix,
                actualTokenIds.subList(0, expectedPrefixLen).toIntArray(),
            )
        }
    }
}
