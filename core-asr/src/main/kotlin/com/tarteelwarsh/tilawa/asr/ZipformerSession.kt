package com.tarteelwarsh.tilawa.asr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/** Sortie d'un appel au modele pour un chunk - log_probs deja tronque a outLen frames (pas de padding residuel). */
data class ChunkOutput(val logProbs: Array<FloatArray>, val outLen: Int)

/**
 * Session ONNX Runtime pour le Zipformer streaming - gere les 98 tenseurs
 * d'etat entre appels (voir streaming_meta.json / core-asr/README.md). Une
 * instance = une session de reconnaissance continue ; resetState() pour
 * repartir a zero (declenche par core-segmenter, voir la decision "pas de
 * rejeu de l'audio retenu apres coupure" actee pour ce projet).
 *
 * API verifiee directement sur la Javadoc onnxruntime (pas supposee) :
 * OnnxTensor.createTensor(env, FloatBuffer/IntBuffer/LongBuffer, long[] shape),
 * OnnxTensor.getFloatBuffer()/getIntBuffer()/getLongBuffer(),
 * TensorInfo.type (OnnxJavaType), OrtSession.Result.get(int)/close().
 */
class ZipformerSession(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    val meta: StreamingMeta,
) : AutoCloseable {

    private var states: Array<OnnxTensor> = createInitialStates()

    private fun createInitialStates(): Array<OnnxTensor> =
        Array(meta.numStateTensors) { i -> zeroTensor(meta.stateShapes[i], meta.stateDtypes[i]) }

    /** Remet les 98 etats a zero - jamais de rejeu de l'audio retenu (voir core-asr/README.md, decision actee). */
    fun resetState() {
        for (s in states) s.close()
        states = createInitialStates()
    }

    /**
     * x : frames Fbank aplaties (rawChunkLen * numMelBins floats, ordre ligne
     * par ligne). xLen : nombre de frames reellement valides dans x (<=
     * rawChunkLen - le reste est du padding LOG_EPS, comme cote Python).
     */
    /**
     * @param keepState true for a COMMITTED window (the 98 states advance);
     *   false for a SPECULATIVE one - the returned states are copied, read and
     *   then closed, leaving the committed cache exactly as it was. That is the
     *   Kotlin equivalent of Python's `[s.clone() for s in self.states]`: the
     *   provisional pass must be able to see the newest audio without the
     *   committed stream ever remembering that it did.
     */
    fun runChunk(x: FloatArray, xLen: Long, keepState: Boolean = true): ChunkOutput {
        val numFrames = x.size / meta.numMelBins
        val xShape = longArrayOf(1, numFrames.toLong(), meta.numMelBins.toLong())

        return OnnxTensor.createTensor(env, FloatBuffer.wrap(x), xShape).use { xTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(xLen)), longArrayOf(1)).use { xLensTensor ->
                val inputs = LinkedHashMap<String, OnnxTensor>()
                inputs["x"] = xTensor
                inputs["x_lens"] = xLensTensor
                for (i in states.indices) inputs["state_$i"] = states[i]

                session.run(inputs).use { result ->
                    val logProbsTensor = result.get(0) as OnnxTensor
                    val outLensTensor = result.get(1) as OnnxTensor
                    val outLen = outLensTensor.longBuffer.get(0).toInt()

                    val logProbsShape = logProbsTensor.info.shape // [1, T, numClasses]
                    val totalT = logProbsShape[1].toInt()
                    val numClasses = logProbsShape[2].toInt()
                    val flat = FloatArray(totalT * numClasses)
                    logProbsTensor.floatBuffer.get(flat)
                    val logProbs = Array(outLen) { t -> FloatArray(numClasses) { c -> flat[t * numClasses + c] } }

                    // Copie TOUS les etats vers des tenseurs frais et
                    // independants de `result` (jamais de reutilisation
                    // directe : result.close() liberait sinon la memoire
                    // native de tout etat qu'on aurait garde tel quel) - et
                    // recast au passage si le dtype de sortie differe du
                    // dtype d'entree attendu (seul cas reel : state_97
                    // processed_lens, INT64 en sortie car
                    // processed_lens+x_lens promeut le type dans le graphe,
                    // mais INT32 attendu en entree - meme piege que cote
                    // Python, verifie ici pour TOUS les etats par
                    // precaution, pas seulement celui-la).
                    val newStates = Array(meta.numStateTensors) { i ->
                        copyState(result.get(2 + i) as OnnxTensor, meta.stateDtypes[i], meta.stateShapes[i])
                    }
                    if (keepState) {
                        for (s in states) s.close()
                        states = newStates
                    } else {
                        // Speculative: discard, do NOT advance the committed cache.
                        for (s in newStates) s.close()
                    }

                    ChunkOutput(logProbs, outLen)
                }
            }
        }
    }

    private fun copyState(output: OnnxTensor, targetDtype: String, shape: LongArray): OnnxTensor {
        val n = shape.fold(1L) { a, b -> a * b }.toInt()
        return when (targetDtype) {
            "float32" -> {
                check(output.info.type == OnnxJavaType.FLOAT) {
                    "type inattendu pour un etat float32 en sortie du graphe : ${output.info.type}"
                }
                val copy = FloatArray(n)
                output.floatBuffer.get(copy)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(copy), shape)
            }
            "int32" -> {
                val copy = IntArray(n)
                when (output.info.type) {
                    OnnxJavaType.INT64 -> {
                        val src = output.longBuffer
                        for (i in 0 until n) copy[i] = src.get(i).toInt()
                    }
                    OnnxJavaType.INT32 -> output.intBuffer.get(copy)
                    else -> error("type inattendu pour un etat int32 en sortie du graphe : ${output.info.type}")
                }
                OnnxTensor.createTensor(env, IntBuffer.wrap(copy), shape)
            }
            else -> error("dtype d'etat non gere : $targetDtype")
        }
    }

    private fun zeroTensor(shape: LongArray, dtype: String): OnnxTensor {
        val n = shape.fold(1L) { a, b -> a * b }.toInt()
        return when (dtype) {
            "float32" -> OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(n)), shape)
            "int32" -> OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(n)), shape)
            else -> error("dtype d'etat non gere : $dtype")
        }
    }

    override fun close() {
        for (s in states) s.close()
        session.close()
    }
}
