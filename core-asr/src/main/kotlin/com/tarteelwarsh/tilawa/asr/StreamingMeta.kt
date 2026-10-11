package com.tarteelwarsh.tilawa.asr

import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonArray
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonInt
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonString
import java.io.InputStream

/** Miroir de zipformer_streaming_onnx_standard/streaming_meta.json - contrat exact du graphe ONNX exporte, jamais suppose. */
data class StreamingMeta(
    val chunkSize: Int,
    val leftContextFrames: Int,
    val padLength: Int,
    val rawChunkLen: Int,
    val numStateTensors: Int,
    val numClasses: Int,
    val blankId: Int,
    /** Id of the waqf symbol ("s") in the ASR vocab, or -1 for an older
     * checkpoint whose vocab has none - in which case the model marks no
     * boundaries and the caller has no segmentation signal at all. */
    val waqfId: Int,
    val sampleRate: Int,
    val numMelBins: Int,
    val stateShapes: List<LongArray>,
    val stateDtypes: List<String>,
) {
    companion object {
        fun parse(stream: InputStream): StreamingMeta {
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JsonParser.parse(text).asJsonObject()
            val stateShapes = root.getValue("state_shapes").asJsonArray().map { shape ->
                shape.asJsonArray().map { it.asJsonInt().toLong() }.toLongArray()
            }
            val stateDtypes = root.getValue("state_dtypes").asJsonArray().map { it.asJsonString() }
            return StreamingMeta(
                chunkSize = root.getValue("chunk_size").asJsonInt(),
                leftContextFrames = root.getValue("left_context_frames").asJsonInt(),
                padLength = root.getValue("pad_length").asJsonInt(),
                rawChunkLen = root.getValue("raw_chunk_len").asJsonInt(),
                numStateTensors = root.getValue("num_state_tensors").asJsonInt(),
                numClasses = root.getValue("num_classes").asJsonInt(),
                blankId = root.getValue("blank_id").asJsonInt(),
                // Optional so an older streaming_meta.json still parses.
                waqfId = runCatching { root.getValue("waqf_id").asJsonInt() }.getOrDefault(-1),
                sampleRate = root.getValue("sample_rate").asJsonInt(),
                numMelBins = root.getValue("num_mel_bins").asJsonInt(),
                stateShapes = stateShapes,
                stateDtypes = stateDtypes,
            )
        }
    }
}
