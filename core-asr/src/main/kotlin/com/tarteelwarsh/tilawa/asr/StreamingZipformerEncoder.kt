package com.tarteelwarsh.tilawa.asr

import com.tarteelwarsh.tilawa.fbank.StreamingFbank
import com.tarteelwarsh.tilawa.logic.tokenizer.RunAwareTokenizer

/**
 * Decomposition chronometree reelle d'un appel a `pushAudio()`/`flush()` -
 * `fbankNanos` couvre `acceptWaveform()` + la lecture des frames pretes,
 * `asrNanos` couvre uniquement `ZipformerSession.runChunk()` (l'appel ONNX)
 * + le decodage CTC (argmax, negligeable). Accumule sur potentiellement
 * plusieurs fenetres si un seul appel en complete plus d'une. Champs `var`
 * volontairement (accumulateur passe par l'appelant, pas recree a chaque
 * fenetre interne).
 */
data class EncoderStageTiming(var fbankNanos: Long = 0, var asrNanos: Long = 0)

/**
 * Pilote incremental de l'encodeur Zipformer - la contrepartie VRAIMENT
 * streaming de `ZipformerChunker`/`generate_asr_goldens.py` (qui operent sur
 * un buffer de frames deja entierement connu, avec un padding de queue fixe
 * pour clore le tout dernier chunk).
 *
 * `pushAudio()` (chemin live, utilise a chaque chunk micro) : une fenetre de
 * `chunkLength` frames n'est consommee que lorsqu'elle est integralement
 * disponible a partir d'audio REEL - jamais de padding. Consequence
 * pratique : `text()` peut etre en retard de jusqu'a `chunkLength` frames
 * (~770ms) sur l'audio le plus recent - c'est la latence algorithmique deja
 * documentee (settle ~640ms + pad_length ~130ms), pas un defaut de ce
 * pilote.
 *
 * `flush()` (chemin fin-de-session, PAS appele a chaque chunk) : reproduit
 * EXACTEMENT le padding de queue du mode batch (`pad_length + TAIL_PAD_LEN`
 * frames de silence LOG_EPS, cf. `scripts/generate_asr_goldens.py`) pour
 * clore le ou les derniers chunks encore incomplets. Prouve par
 * `StreamingEncoderFlushGoldenDeviceTest` : `pushAudio()` seul reproduit un
 * prefixe exact (22/24 tokens) de la reference dore batch ; en enchainant
 * `flush()` ensuite, on obtient les 24/24 tokens EXACTS - pas juste une
 * hypothese plausible sur pourquoi les 2 derniers manquaient.
 */
class StreamingZipformerEncoder(
    private val session: ZipformerSession,
    private val decoder: StreamingCtcDecoder,
) : AutoCloseable {

    private val meta = session.meta
    private val rawChunkSize = meta.chunkSize * 2
    private val chunkLength = rawChunkSize + meta.padLength


    // Memes valeurs que scripts/generate_asr_goldens.py (LOG_EPS = ln(1e-10), TAIL_PAD_LEN = 30).
    private val logEps = kotlin.math.ln(1e-10f)
    private val tailPadFrames = 30

    private var fbank = StreamingFbank(StreamingFbank.Config.ZIPFORMER)
    // Starts at -padLength, not 0: the first window is [-padLength, rawChunkSize)
    // so its runway is synthetic silence and output covers real frame 0 onward.
    private var readPointer = -meta.padLength

    /**
     * Real frame if available, otherwise LOG_EPS silence. Pads BOTH ends:
     *
     * - index < 0: before the stream began. Without this the first window would
     *   start at frame 0 and its first padLength frames would be eaten as front-end
     *   runway, covered by no output at all - losing the first 130ms of every
     *   session, which is exactly where the opening letter is. Dani_app's
     *   ZipformerStream._frame_at does the same.
     * - index >= numFramesReady: audio that has not arrived yet, used by the
     *   speculative tail.
     */
    private fun frameAt(index: Int): FloatArray =
        if (index in 0 until fbank.numFramesReady) fbank.getFrame(index)
        else FloatArray(meta.numMelBins) { logEps }

    /** Consomme tous les chunks complets tant que `readPointer + chunkLength <= limit` (jamais de padding). */
    private fun consumeStrict(limit: Int, timing: EncoderStageTiming?) {
        while (limit - readPointer >= chunkLength) {
            runWindowAt(readPointer, timing)
            readPointer += rawChunkSize
        }
    }

    /**
     * Consomme tous les chunks tant que `readPointer < limit`, meme le
     * dernier si son empan deborde de `limit` - `frameAt()` comble alors
     * avec du LOG_EPS, exactement comme le `np.pad` du script batch.
     * `limit` doit deja inclure le padding de queue (voir flush()).
     */
    private fun consumePadded(limit: Int, timing: EncoderStageTiming?) {
        while (readPointer < limit) {
            runWindowAt(readPointer, timing)
            readPointer += rawChunkSize
        }
    }

    private fun runWindowAt(start: Int, timing: EncoderStageTiming?) {
        val tFbank0 = System.nanoTime()
        val window = FloatArray(chunkLength * meta.numMelBins)
        for (i in 0 until chunkLength) {
            val frame = frameAt(start + i)
            System.arraycopy(frame, 0, window, i * meta.numMelBins, meta.numMelBins)
        }
        timing?.let { it.fbankNanos += System.nanoTime() - tFbank0 }

        val tAsr0 = System.nanoTime()
        val output = session.runChunk(window, chunkLength.toLong())
        val predIds = (0 until output.outLen).map { t ->
            var best = 0
            var bestVal = output.logProbs[t][0]
            for (c in 1 until output.logProbs[t].size) {
                if (output.logProbs[t][c] > bestVal) { bestVal = output.logProbs[t][c]; best = c }
            }
            best
        }
        decoder.push(predIds)
        timing?.let { it.asrNanos += System.nanoTime() - tAsr0 }
    }

    /**
     * Nouveau PCM float32 [-1,1] a 16kHz (meme grain que core-audio, mais
     * toute taille acceptee). `timing`, si fourni, ACCUMULE (pas remis a
     * zero ici) - a l'appelant de fournir un accumulateur frais par chunk
     * s'il veut une mesure par-chunk plutot que cumulative sur la session.
     */
    fun pushAudio(
        chunk: FloatArray,
        timing: EncoderStageTiming? = null,
        /**
         * Set false when the pipeline is behind. The speculative pass is a whole
         * extra forward on EVERY 100ms chunk, while a committed window only runs
         * once per rawChunkSize (640ms) - so on a device where one forward costs
         * more than the chunk period it is what makes the backlog grow without
         * bound. Skipping it is safe precisely because it is throwaway: no state
         * advances, nothing is cached. Dropping a chunk instead would corrupt the
         * fbank/CTC stream, which is why that is never an option here.
         */
        runSpeculative: Boolean = true,
    ) {
        val tFbank0 = System.nanoTime()
        fbank.acceptWaveform(meta.sampleRate.toFloat(), chunk)
        timing?.let { it.fbankNanos += System.nanoTime() - tFbank0 }
        consumeStrict(fbank.numFramesReady, timing)
        // Clear rather than keep when skipping: the committed stream may have
        // advanced past what these tokens described, and stale provisional text
        // would then duplicate the committed text rather than extend it.
        if (runSpeculative) runSpeculativeTail(timing) else decoder.clearSpeculative()
    }

    /**
     * PROVISIONAL text for the audio that has arrived but not yet filled a whole
     * committed block. Without this, text only appears when a block commits -
     * in visible 160ms steps - instead of growing continuously.
     *
     * The window is the SAME fixed [readPointer, readPointer + chunkLength) that
     * the next commit will use, with frames past numFramesReady filled with
     * LOG_EPS. Fixed width is not a compromise but a requirement: the exported
     * ONNX graph accepts exactly one input length (SimpleDownsample bakes its
     * reshape at trace time), and padding to that width makes every forward call
     * - committed and speculative alike - exactly chunkLength frames.
     *
     * Padding changes the result versus encoding only the real frames, because
     * SimpleDownsample weighted-sums groups of frames with no mask. That is
     * accepted: this reading is provisional and is replaced as the audio commits.
     * Measured end to end in Python (warsh_apk/experiment_padded_tail.py), the
     * padded tail is indistinguishable from the exact one - same result count,
     * same waqf cuts, same trust ceiling, same settle point, identical final text.
     *
     * Runs with keepState=false, so the committed cache never advances.
     */
    private fun runSpeculativeTail(timing: EncoderStageTiming?) {
        val nextFrame = readPointer + meta.padLength
        var tail = fbank.numFramesReady - nextFrame
        // Round down to even: the front end strides by 2, so an odd frame cannot
        // complete an output frame - it would only perturb the last one.
        tail -= tail % 2
        decoder.clearSpeculative()
        if (tail < MIN_TAIL_FRAMES) return

        val tFbank0 = System.nanoTime()
        val window = FloatArray(chunkLength * meta.numMelBins)
        for (i in 0 until chunkLength) {
            val frame = frameAt(readPointer + i)
            System.arraycopy(frame, 0, window, i * meta.numMelBins, meta.numMelBins)
        }
        timing?.let { it.fbankNanos += System.nanoTime() - tFbank0 }

        val tAsr0 = System.nanoTime()
        val output = session.runChunk(window, chunkLength.toLong(), keepState = false)
        // Keep only the output frames covering REAL audio. One output frame is 4
        // fbank frames (10ms -> /2 in encoder_embed -> /2 in the encoder), so the
        // padded region emits nothing.
        val keep = minOf(tail / 4, output.outLen)
        val predIds = (0 until keep).map { t ->
            var best = 0
            var bestVal = output.logProbs[t][0]
            for (c in 1 until output.logProbs[t].size) {
                if (output.logProbs[t][c] > bestVal) { bestVal = output.logProbs[t][c]; best = c }
            }
            best
        }
        decoder.pushSpeculative(predIds)
        timing?.let { it.asrNanos += System.nanoTime() - tAsr0 }
    }

    /**
     * Clot la session courante : complete le(s) dernier(s) chunk(s) encore
     * en attente avec du silence LOG_EPS, exactement comme le fait le script
     * de reference batch en fin de fichier. A appeler uniquement quand
     * l'utilisateur arrete reellement l'enregistrement (pas a chaque chunk -
     * ca romprait la latence algorithmique documentee) ou en toute fin d'un
     * test qui rejoue un extrait fige.
     */
    fun flush(timing: EncoderStageTiming? = null) {
        consumePadded(fbank.numFramesReady + meta.padLength + tailPadFrames, timing)
    }

    /** Committed + provisional - what the UI shows. */
    fun text(): String = decoder.text()

    /** Committed only. */
    fun committedText(): String = decoder.committedText()
    fun tokenIds(): List<Int> = decoder.tokenIds()

    /** Waqf segmentation - see StreamingCtcDecoder. The waqf id comes from the
     * ASR vocab (streaming_meta.json: waqf_id), not from a constant here. */
    fun segmentText(waqfId: Int): StreamingCtcDecoder.Segment = decoder.segmentText(waqfId)

    /** Drop the finished utterance's tokens. Does NOT reset the model state:
     * the cache must survive the waqf so the encoder keeps its left context. */
    fun cutAtWaqf(waqfId: Int): Boolean = decoder.cutAtWaqf(waqfId)



    /** Reset complet - aucun rejeu de l'audio deja consomme (decision actee, voir core-asr/README.md). */
    fun resetState() {
        fbank.close()
        fbank = StreamingFbank(StreamingFbank.Config.ZIPFORMER)
        readPointer = -meta.padLength
        session.resetState()
        decoder.reset()
    }

    override fun close() {
        fbank.close()
        session.close()
    }

    companion object {
        /** Smallest tail worth a speculative pass. A window carries padLength
         * frames of runway, so ((tail+13-7)/2)-3 >= 1 needs tail >= 2. */
        private const val MIN_TAIL_FRAMES = 2

        fun create(env: ai.onnxruntime.OrtEnvironment, ortSession: ai.onnxruntime.OrtSession, meta: StreamingMeta, tokenizer: RunAwareTokenizer): StreamingZipformerEncoder {
            val zipformerSession = ZipformerSession(env, ortSession, meta)
            val decoder = StreamingCtcDecoder(tokenizer, meta.blankId)
            return StreamingZipformerEncoder(zipformerSession, decoder)
        }
    }
}
