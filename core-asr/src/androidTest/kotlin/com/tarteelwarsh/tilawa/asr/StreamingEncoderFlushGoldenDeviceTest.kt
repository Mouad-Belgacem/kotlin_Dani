package com.tarteelwarsh.tilawa.asr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonArray
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonInt
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonString
import com.tarteelwarsh.tilawa.logic.tokenizer.RunAwareTokenizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Complete StreamingEncoderGoldenDeviceTest (prefixe 22/24 sans padding) en
 * prouvant concretement l'explication des 2 tokens manquants, plutot que de
 * la laisser comme hypothese plausible non testee : on nourrit le meme flux
 * live par chunks de 1600 echantillons (comme l'autre test), PUIS on appelle
 * `flush()` - qui applique exactement le meme padding de queue LOG_EPS
 * (`pad_length + TAIL_PAD_LEN` frames) que `scripts/generate_asr_goldens.py`.
 * Si l'explication est correcte, le resultat doit etre 24/24 EXACT (ids et
 * texte), pas juste "plausiblement plus complet".
 */
@RunWith(AndroidJUnit4::class)
class StreamingEncoderFlushGoldenDeviceTest {

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
    fun flushAfterIncrementalFeedingMatchesTheBatchReferenceExactly() {
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
        val goldenTokenIds = goldenRoot.getValue("tokenIds").asJsonArray().map { it.asJsonInt() }.toIntArray()
        val goldenText = goldenRoot.getValue("decodedText").asJsonString()

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

            val prefixTokenIds = encoder.tokenIds()
            android.util.Log.i(
                "StreamingEncoderFlushGoldenDeviceTest",
                "avant flush() : ${prefixTokenIds.size} tokens (prefixe attendu, cf. StreamingEncoderGoldenDeviceTest)",
            )

            encoder.flush()

            val finalTokenIds = encoder.tokenIds()
            val finalText = encoder.text()
            android.util.Log.i(
                "StreamingEncoderFlushGoldenDeviceTest",
                "apres flush() : ${finalTokenIds.size} tokens, texte=\"$finalText\"",
            )

            assertEquals(
                "flush() doit produire EXACTEMENT le meme nombre de tokens que la reference batch",
                goldenTokenIds.size,
                finalTokenIds.size,
            )
            assertArrayEquals(
                "ids CTC apres flush() differents de la reference Python batch (24/24 attendus)",
                goldenTokenIds,
                finalTokenIds.toIntArray(),
            )
            assertEquals(
                "texte decode apres flush() different de la reference Python batch",
                goldenText,
                finalText,
            )
        }
    }
}
