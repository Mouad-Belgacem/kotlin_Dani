package com.tarteelwarsh.tilawa.pipeline

/**
 * Decomposition chronometree REELLE d'un appel a `PipelineSession.processChunk()`,
 * chrono par etage (pas une attribution par raisonnement de conception) :
 * - `asrFbankNanos`/`asrModelNanos` : cote Zipformer (core-asr,
 *   `EncoderStageTiming`) - extraction Fbank vs inference ONNX.
 * - `segmenterFbankNanos`/`segmenterModelNanos` : cote segmenter
 *   (core-segmenter, `SegmenterStageTiming`) - idem.
 * - `correctorNanos` : `Corrector.correct()` + `getAya()` (core-logic).
 * `totalFbankNanos` combine les deux frontends Fbank (config Zipformer +
 * config segmenter, deux moteurs `kaldi_native_fbank` distincts - voir
 * core-fbank/README.md) en une seule categorie, comme demande, sans perdre
 * le detail par cote (utile si l'un des deux domine inopinement l'autre).
 */
data class ChunkStageTiming(
    var asrFbankNanos: Long = 0,
    var asrModelNanos: Long = 0,
    var segmenterFbankNanos: Long = 0,
    var segmenterModelNanos: Long = 0,
    var correctorNanos: Long = 0,
) {
    val totalFbankNanos: Long get() = asrFbankNanos + segmenterFbankNanos
    val totalNanos: Long get() = totalFbankNanos + asrModelNanos + segmenterModelNanos + correctorNanos
}
