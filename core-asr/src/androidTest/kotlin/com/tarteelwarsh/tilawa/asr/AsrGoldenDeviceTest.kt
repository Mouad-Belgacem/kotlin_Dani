package com.tarteelwarsh.tilawa.asr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tarteelwarsh.tilawa.fbank.StreamingFbank
import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonArray
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonInt
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonString
import com.tarteelwarsh.tilawa.logic.tokenizer.RunAwareTokenizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test dore execute sur le vrai device : rejoue les 5 premieres secondes de
 * Dani_app/audio.wav a travers le pipeline reel StreamingFbank (core-fbank,
 * deja valide) -> ZipformerChunker -> ZipformerSession (ONNX Runtime Android)
 * -> StreamingCtcDecoder, et compare au resultat de reference genere cote
 * Python par scripts/generate_asr_goldens.py (qui rejoue lui-meme exactement
 * la boucle de scripts/verify_onnx_export_standard.py). La comparaison
 * porte sur les log_probs chunk par chunk (tolerance numerique, comme pour
 * core-fbank) ET sur le texte/les ids decodes (egalite stricte - c'est la
 * garantie qui compte reellement en usage).
 */
@RunWith(AndroidJUnit4::class)
class AsrGoldenDeviceTest {

    private data class ChunkGolden(val outLen: Int, val logProbs: Array<FloatArray>)
    private data class Golden(
        val samples: FloatArray,
        val numClasses: Int,
        val chunks: List<ChunkGolden>,
        val decodedText: String,
        val tokenIds: List<Int>,
        val blankId: Int,
    )

    private fun loadGolden(): Golden {
        val context = InstrumentationRegistry.getInstrumentation().context

        val audioBytes = context.assets.open("goldens/audio_golden.bin").use { it.readBytes() }
        val audioBuf = ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN)
        val numSamples = audioBuf.int
        val samples = FloatArray(numSamples)
        audioBuf.asFloatBuffer().get(samples, 0, numSamples)

        val logProbsBytes = context.assets.open("goldens/log_probs_golden.bin").use { it.readBytes() }
        val lpBuf = ByteBuffer.wrap(logProbsBytes).order(ByteOrder.LITTLE_ENDIAN)
        val numChunks = lpBuf.int
        val numClasses = lpBuf.int
        val chunks = ArrayList<ChunkGolden>(numChunks)
        repeat(numChunks) {
            val outLen = lpBuf.int
            val flat = FloatArray(outLen * numClasses)
            lpBuf.asFloatBuffer().get(flat, 0, flat.size)
            lpBuf.position(lpBuf.position() + flat.size * 4)
            val logProbs = Array(outLen) { t -> FloatArray(numClasses) { c -> flat[t * numClasses + c] } }
            chunks.add(ChunkGolden(outLen, logProbs))
        }

        val metaText = context.assets.open("goldens/asr_golden_meta.json").use {
            it.bufferedReader(Charsets.UTF_8).readText()
        }
        val metaRoot = JsonParser.parse(metaText).asJsonObject()
        val decodedText = metaRoot.getValue("decodedText").asJsonString()
        val tokenIds = metaRoot.getValue("tokenIds").asJsonArray().map { it.asJsonInt() }
        val blankId = metaRoot.getValue("blankId").asJsonInt()

        return Golden(samples, numClasses, chunks, decodedText, tokenIds, blankId)
    }

    /** Copie l'asset .onnx (248 Mo, non commite - voir mobile_app/.gitignore) vers un fichier reel : ORT Android n'accepte pas un descripteur d'asset compresse comme chemin de modele. */
    private fun materializeModel(): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        val dest = File(context.cacheDir, "zipformer_warsh_standard_streaming.onnx")
        // openFd() n'aboutit que si l'asset est stocke non compresse (voir
        // noCompress += "onnx" dans build.gradle.kts) ; si l'environnement de
        // build ne l'a pas respecte pour une raison quelconque, on retombe
        // simplement sur une copie systematique plutot que de planter le test.
        val assetSize = try {
            context.assets.openFd("model/zipformer_warsh_standard_streaming.onnx").use { it.length }
        } catch (e: java.io.IOException) {
            -1L
        }
        if (assetSize < 0 || !dest.exists() || dest.length() != assetSize) {
            context.assets.open("model/zipformer_warsh_standard_streaming.onnx").use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 1 shl 20)
                }
            }
        }
        return dest
    }

    @Test
    fun matchesThePythonReferencePipeline() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val golden = loadGolden()
        val meta = context.assets.open("model/streaming_meta.json").use { StreamingMeta.parse(it) }
        assertEquals(golden.numClasses, meta.numClasses)
        assertEquals(golden.blankId, meta.blankId)

        // 1. Fbank sur le meme audio, deja valide numeriquement contre Python (core-fbank).
        val frames = StreamingFbank().use { fbank ->
            fbank.acceptWaveform(meta.sampleRate.toFloat(), golden.samples)
            fbank.inputFinished()
            (0 until fbank.numFramesReady).map { fbank.getFrame(it) }
        }

        // 2. Fenetrage identique a scripts/generate_asr_goldens.py.
        val chunks = ZipformerChunker(meta).chunksFor(frames)
        assertEquals("nombre de chunks different de la reference Python", golden.chunks.size, chunks.size)

        val modelFile = materializeModel()
        val env = OrtEnvironment.getEnvironment()
        val sessionOptions = OrtSession.SessionOptions()
        val session = env.createSession(modelFile.absolutePath, sessionOptions)

        val tokenizer = RunAwareTokenizer.fromResource()
        val decoder = StreamingCtcDecoder(tokenizer, meta.blankId)

        var maxDiff = 0f
        ZipformerSession(env, session, meta).use { zipformer ->
            for ((i, chunk) in chunks.withIndex()) {
                val expected = golden.chunks[i]
                val output = zipformer.runChunk(chunk.features, chunk.featLen.toLong())

                assertEquals("outLen different au chunk $i", expected.outLen, output.outLen)

                for (t in 0 until output.outLen) {
                    for (c in 0 until meta.numClasses) {
                        val diff = kotlin.math.abs(output.logProbs[t][c] - expected.logProbs[t][c])
                        maxDiff = maxOf(maxDiff, diff)
                    }
                }

                val predIds = (0 until output.outLen).map { t ->
                    var bestIdx = 0
                    var bestVal = output.logProbs[t][0]
                    for (c in 1 until meta.numClasses) {
                        if (output.logProbs[t][c] > bestVal) {
                            bestVal = output.logProbs[t][c]
                            bestIdx = c
                        }
                    }
                    bestIdx
                }
                decoder.push(predIds)
            }
        }

        android.util.Log.i("AsrGoldenDeviceTest", "maxDiff log_probs vs reference Python = $maxDiff")
        // Tolerance large pour d'eventuelles differences d'arrondi float32
        // entre le runtime ORT desktop (Python, reference) et le runtime
        // ORT mobile (meme moteur, builds/optimisations differentes) - une
        // vraie divergence (etat mal recast, fenetrage faux) donnerait un
        // ecart de plusieurs ordres de grandeur au-dessus de ca.
        assertTrue("ecart trop grand vs reference Python (pipeline divergent ?): $maxDiff", maxDiff < 1e-2f)

        assertEquals("texte decode different de la reference Python", golden.decodedText, decoder.text())
        assertArrayEquals(
            "ids CTC apres collapse differents de la reference Python",
            golden.tokenIds.toIntArray(),
            decoder.tokenIds().toIntArray(),
        )
    }
}
