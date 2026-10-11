package com.tarteelwarsh.tilawa.asr

/**
 * Port fidele de la boucle de fenetrage de scripts/verify_onnx_export_standard.py
 * (elle-meme le pendant numpy de SingleStreamBuffer, streaming_pipeline_standard.py) :
 * chaque chunk consomme `rawChunkSize + padLength` frames Fbank, mais le
 * pointeur n'avance que de `rawChunkSize` - les `padLength` dernieres frames
 * d'un chunk sont donc re-consommees comme tete du chunk suivant
 * (chevauchement volontaire, latence de lookahead - voir
 * core-audio/README.md et core-asr/README.md).
 */
class ZipformerChunker(private val meta: StreamingMeta, private val tailPadFrames: Int = 30) {
    private val rawChunkSize = meta.chunkSize * 2
    private val chunkLength = rawChunkSize + meta.padLength
    private val logEps = kotlin.math.ln(1e-10f)

    data class Chunk(val features: FloatArray, val featLen: Int)

    /** frames : liste de vecteurs Fbank (chacun de taille meta.numMelBins), dans l'ordre chronologique - un flux fige (pas incremental) pour cette version. */
    fun chunksFor(frames: List<FloatArray>): List<Chunk> {
        val numMel = meta.numMelBins
        val silentFrame = FloatArray(numMel) { logEps }

        val padded = ArrayList<FloatArray>(frames.size + meta.padLength + tailPadFrames)
        padded.addAll(frames)
        repeat(meta.padLength + tailPadFrames) { padded.add(silentFrame) }

        val chunks = ArrayList<Chunk>()
        var numProcessed = 0
        while (numProcessed < padded.size) {
            val end = minOf(numProcessed + chunkLength, padded.size)
            val window = ArrayList(padded.subList(numProcessed, end))
            var featLen = window.size
            numProcessed += rawChunkSize

            if (featLen < chunkLength) {
                repeat(chunkLength - featLen) { window.add(silentFrame) }
                featLen = chunkLength
            }

            val flat = FloatArray(chunkLength * numMel)
            for (i in 0 until chunkLength) {
                System.arraycopy(window[i], 0, flat, i * numMel, numMel)
            }
            chunks.add(Chunk(flat, featLen))
        }
        return chunks
    }
}
