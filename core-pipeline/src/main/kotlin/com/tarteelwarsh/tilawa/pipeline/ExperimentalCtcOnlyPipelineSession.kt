package com.tarteelwarsh.tilawa.pipeline

import com.tarteelwarsh.tilawa.asr.EncoderStageTiming
import com.tarteelwarsh.tilawa.asr.StreamingZipformerEncoder
import com.tarteelwarsh.tilawa.logic.correction.Corrector
import com.tarteelwarsh.tilawa.logic.correction.Finder

/**
 * EXPERIENCE DE MESURE - PAS le pipeline de production (`PipelineSession`,
 * inchange, toujours le chemin reel). Variante DELIBEREMENT temporaire qui
 * retire `core-segmenter` du pipeline : la fin d'un enonce est marquee par le
 * modele lui-meme, via le symbole waqf "s" du checkpoint waqf-aware.
 *
 * Le symbole "s" COMMITTED est desormais la SEULE chose qui termine une
 * phrase. Un repli fonde sur la duree des blancs CTC a existe ici et a ete
 * RETIRE : un blanc CTC signifie "aucun symbole NOUVEAU sur cette frame", pas
 * "aucun son". Tenir un madd long produit exactement cela - le token de run
 * est emis une fois puis les frames suivantes sont blanches alors que le
 * reciteur vocalise toujours - et le repli coupait donc la phrase au milieu du
 * madd. Ne pas le reintroduire sans une vraie mesure acoustique (energie/RMS),
 * que ce projet ne calcule nulle part aujourd'hui.
 *
 * Le `Corrector` et le rendu de couleur restent EXACTEMENT le meme code que
 * `PipelineSession` (memes classes `core-logic`, memes garde-fous
 * Bismillah/Istiadha, meme `add_harakat`) - seule la SOURCE du signal de
 * fin d'enonce change. Deux simplifications ASSUMEES, absentes de
 * `PipelineSession` (la bookkeeping precise qu'elles remplacent n'a plus de
 * sens sans VAD sample-accurate) :
 * - pas de fenetre `[iLast,jLast]` ni de `PAD_SAMPLES` : `encoder.text()`
 *   est utilise tel quel dans son entier - le seul gate restant est
 *   `MIN_TILAWA_CHARS`.
 * - `repeat` compare `tilawa` seul (`wasl` est desormais fige a `true`,
 *   voir plus bas - une comparaison contre `prevWasl` n'aurait plus de
 *   sens), sans la tolerance de position `SEGMENT_MATCH_TOLERANCE` (il n'y
 *   a plus de position de segment fiable sans VAD).
 *
 * Troisieme simplification ASSUMEE, ajoutee apres une premiere mesure ayant
 * revele que `corrector.correct()` etait, sans throttle propre, rappele a
 * CHAQUE chunk (100ms) des que `tilawa` depassait `MIN_TILAWA_CHARS` - son
 * propre cout (100-175ms/appel mesure) depassait alors a lui seul le budget
 * 100ms/chunk (voir `app/README.md`, section 5) :
 * - `corrector.correct()` est maintenant throttle explicitement a
 *   `PipelineConfig.EXPERIMENTAL_CORRECTOR_CALL_INTERVAL_SAMPLES` (200ms
 *   par defaut), INDEPENDAMMENT du signal de coupure ci-dessus (qui reste
 *   lu/applique a CHAQUE chunk - lire le waqf et couper est quasi gratuit,
 *   seul l'appel Corrector est couteux). Reproduit le throttle "gratuit" dont beneficiait deja
 *   `corrector.correct()` en production (place derriere le throttle
 *   d'appel segmenteur dans `PipelineSession`), que cette experience avait
 *   supprime par accident en retirant le segmenteur.
 * - le parametre `wasl` passe a `corrector.correct()` est desormais FIGE a
 *   `true` en permanence, plutot que derive du proxy CTC - simplification
 *   ASSUMEE, pas un oubli : le proxy CTC reste bruite (voir plus haut), et
 *   `wasl=false` declenche cote `Corrector` une recherche de re-ancrage
 *   plus couteuse (`cursorRatio`/`globalScore`, voir `Corrector.correct()`)
 *   dont la pertinence dependrait justement de la fiabilite du signal de
 *   coupure - fixer `wasl=true` isole le cout du throttle lui-meme sans
 *   ajouter cette variable. Contrepartie ACCEPTEE : le mecanisme de
 *   re-ancrage apres coupure ne se declenche plus du tout dans cette
 *   experience (a la difference de `PipelineSession`).
 */
class ExperimentalCtcOnlyPipelineSession(
    private val encoder: StreamingZipformerEncoder,
    private val corrector: Corrector,
    private val helpTilawa: Boolean = false,
    /** Id of the waqf symbol in the ASR vocab (streaming_meta.json: waqf_id). */
    private val waqfId: Int,
    /** The same symbol as text, for stripping it out of the tilawa. */
    private val waqfToken: String = "s",
    private val correctorCallIntervalSamples: Int = PipelineConfig.EXPERIMENTAL_CORRECTOR_CALL_INTERVAL_SAMPLES,
) {
    private var audio: FloatArray = FloatArray(1600)
    private var totalSamples: Long = audio.size.toLong()

    private var prevTilawa: String? = null
    private var prevWasl: Boolean? = null

    /** Bumped every time the model marks a stop. The UI drops the provisional
     *  shadow when this changes - see processChunk(). */
    var utteranceEndedCount: Int = 0
        private set
    private var utteranceEndedLatch = false

    /** Why the last processChunk() produced no result - read by the UI's debug line. */
    var lastBail: String = "-"
        private set
    var lastRawLen: Int = 0
        private set

    // Compteur du throttle d'appel au Corrector (voir KDoc de classe) -
    // echantillons ecoules depuis le dernier appel reel de corrector.correct().
    private var samplesSinceLastCorrectorCall = 0

    /**
     * `timing`, si fourni, reutilise le MEME type que `PipelineSession`
     * (`ChunkStageTiming`) pour permettre une comparaison directe, cote
     * appelant, avec le chemin de production - `segmenterFbankNanos`/
     * `segmenterModelNanos` restent honnêtement a 0 (le segmenteur n'est
     * genuinement plus dans la boucle), seuls `asrFbankNanos`/
     * `asrModelNanos`/`correctorNanos` sont remplis.
     */
    fun processChunk(
        chunk: FloatArray,
        timing: ChunkStageTiming? = null,
        /** False when the caller is behind - see StreamingZipformerEncoder.pushAudio. */
        runSpeculative: Boolean = true,
    ): PipelineResult? {
        appendAudio(chunk)
        val encoderTiming = timing?.let { EncoderStageTiming() }
        encoder.pushAudio(chunk, encoderTiming, runSpeculative)
        if (timing != null && encoderTiming != null) {
            timing.asrFbankNanos += encoderTiming.fbankNanos
            timing.asrModelNanos += encoderTiming.asrNanos
        }

        // SEGMENTATION BY THE MODEL - the only segmentation there is. The
        // waqf-aware checkpoint predicts a symbol where the reciter stops, which
        // replaced the acoustic segmenter, and now also the blank-duration proxy
        // that used to back it up (see the class KDoc for why that was removed).
        val segment = encoder.segmentText(waqfId)
        val waqfFound = segment.found
        // Cut ONLY on a committed waqf. One still in the speculative tail is
        // provisional - the tail is recomputed every chunk and it can vanish - so
        // its text is reported but the utterance is not ended.
        val committedWaqf = segment.found && segment.committed

        // The committed waqf is the ONLY thing that ends a phrase.
        //
        // A blank-duration fallback used to live here (finalise after
        // SILENCE_FINALIZE_MS of CTC blanks). Removed deliberately: a CTC blank
        // means "no NEW symbol at this frame", not "no sound". Holding a long madd
        // produces exactly that - the run token is emitted once and the remaining
        // frames are blank while the reciter is still clearly vocalising - so the
        // fallback cut phrases in the middle of a madd. Nothing in this app
        // measures loudness, so blanks were the only silence evidence available,
        // and they are not evidence of silence at all.
        //
        // KNOWN CONSEQUENCE: the model never learnt to mark a TRAILING waqf
        // (training stripped the final "s" from every clip), so when the reciter
        // simply stops, no "s" arrives and the last phrase stays open - rendered
        // provisional - until the session is ended, which finalizeSession() does
        // handle. Accepted: a lingering shadow is a cosmetic fault, cutting a madd
        // in half is a correctness one.
        val cut = committedWaqf
        // Display-only: a waqf with nothing after it means the reciter stopped.
        // Never cuts, never touches the corrector - see PipelineResult.utteranceEnded.
        val utteranceEnded = committedWaqf || (segment.found && segment.trailing)
        // Published OUTSIDE the PipelineResult, because every bail below returns
        // null and would throw this away - and the bails are exactly when it
        // matters. Stop reciting and the text after the waqf is empty, so
        // `short(n<4)` fires and no result is produced at all; the shadow then
        // stayed until the next phrase, which is the "it never clears" report.
        // The UI watches this counter instead, so the transition is bounded by
        // the waqf appearing (one chunk, 100ms) rather than by a correction
        // happening to be emitted.
        if (utteranceEnded && !utteranceEndedLatch) utteranceEndedCount++
        utteranceEndedLatch = utteranceEnded

        // tilawa is the text BEFORE the waqf. segmentText already excludes the
        // waqf it split on; strip any later one so the symbol can never reach the
        // Corrector - Finder's search alphabet is built from the phonetic Quran
        // text, which contains no such character, so Finder.getIndices would throw
        // NoSuchElementException on it (the Kotlin twin of Python's KeyError 's').
        var tilawa = segment.text.replace(waqfToken, "")
        lastRawLen = tilawa.length

        // Throttle explicite du Corrector (voir KDoc de classe) - le flux
        // audio/ASR et le proxy de coupure ci-dessus, eux, viennent deja
        // d'etre nourris/appliques inconditionnellement.
        samplesSinceLastCorrectorCall += chunk.size
        if (samplesSinceLastCorrectorCall < correctorCallIntervalSamples) {
            lastBail = "throttle"
            return null
        }
        samplesSinceLastCorrectorCall = 0

        if (helpTilawa) tilawa = addHarakat(tilawa)
        if (tilawa.length < PipelineConfig.MIN_TILAWA_CHARS) {
            // Still step over a finished utterance even when it is too short to
            // report, or it would be re-reported forever.
            if (cut) finishSegment()
            lastBail = "short(${tilawa.length}<${PipelineConfig.MIN_TILAWA_CHARS})"
            return null
        }

        // wasl = "still going". A waqf found by the model is exactly what makes it
        // false, and that is what tells the Corrector this utterance is terminated
        // (it gates the re-anchoring search and the trailing-colour rule). No
        // longer pinned to true - the pin existed only because the blank proxy was
        // too unreliable to derive it from.
        val wasl = !cut
        val repeat = tilawa == prevTilawa && wasl == prevWasl
        prevTilawa = tilawa
        prevWasl = wasl

        val cursorBefore = corrector.cursor
        val tCorrector0 = System.nanoTime()
        val correction = corrector.correct(tilawa, wasl)
        val (ayaIdc, _) = corrector.getAya(tilawa)
        timing?.let { it.correctorNanos += System.nanoTime() - tCorrector0 }

        if (!corrector.settled && correction.uthmaniText.isNotEmpty() && isBismillahOrIstiadhaPrefix(correction.uthmaniText)) {
            corrector.score = 0.0
            lastBail = "bismillah-guard"
            if (cut) finishSegment()
            return null
        }

        // Read out first, cut after: the text just reported IS the utterance the
        // cut forgets.
        if (cut) finishSegment()
        lastBail = if (correction.uthmaniText.isEmpty()) "corrector-empty(r<0)" else "ok"

        return PipelineResult(
            wasl = wasl,
            repeat = repeat,
            sura = ayaIdc.first,
            aya = ayaIdc.second,
            text = correction.uthmaniText,
            offset = corrector.cursor - correction.uthmaniText.length + 1,
            colors = correction.colors,
            trust = corrector.score,
            cursor = corrector.cursor,
            prevCursor = cursorBefore,
            settled = corrector.settled,
            utteranceEnded = utteranceEnded,
        )
    }

    /**
     * Cloture EXPLICITE de la session - miroir de `PipelineSession.finalizeSession()`
     * (voir sa KDoc) : `encoder.flush()` puis le texte final restant traite
     * comme une coupure definitive. `wasl=false` ici aussi (contrairement au
     * `wasl` fige a `true` de `processChunk()` ci-dessus) - a la fin d'une
     * session, il n'y a plus de sens a traiter l'enonce comme "encore en
     * cours".
     */
    fun finalizeSession(timing: ChunkStageTiming? = null): PipelineResult? {
        val encoderTiming = timing?.let { EncoderStageTiming() }
        encoder.flush(encoderTiming)
        if (timing != null && encoderTiming != null) {
            timing.asrFbankNanos += encoderTiming.fbankNanos
            timing.asrModelNanos += encoderTiming.asrNanos
        }

        var tilawa = encoder.text()
        if (helpTilawa) tilawa = addHarakat(tilawa)
        if (tilawa.length < PipelineConfig.MIN_TILAWA_CHARS) return null

        val wasl = false
        val repeat = tilawa == prevTilawa
        prevTilawa = tilawa

        val cursorBefore = corrector.cursor
        val tCorrector0 = System.nanoTime()
        val correction = corrector.correct(tilawa, wasl)
        val (ayaIdc, _) = corrector.getAya(tilawa)
        timing?.let { it.correctorNanos += System.nanoTime() - tCorrector0 }

        if (!corrector.settled && correction.uthmaniText.isNotEmpty() && isBismillahOrIstiadhaPrefix(correction.uthmaniText)) {
            corrector.score = 0.0
            return null
        }

        return PipelineResult(
            wasl = wasl,
            repeat = repeat,
            sura = ayaIdc.first,
            aya = ayaIdc.second,
            text = correction.uthmaniText,
            offset = corrector.cursor - correction.uthmaniText.length + 1,
            colors = correction.colors,
            trust = corrector.score,
            cursor = corrector.cursor,
            prevCursor = cursorBefore,
            settled = corrector.settled,
        )
    }

    /**
     * Step over the utterance the model just marked as finished: drop its tokens
     * through the waqf, and drop the audio buffer.
     *
     * Deliberately NOT encoder.resetState(): the ONNX state, the fbank extractor
     * and the read pointer all carry on, so the encoder keeps its 256-frame left
     * context across the boundary. That matches training, where the waqf symbol
     * appeared mid-clip surrounded by real silence rather than at a freshly padded
     * start - and it is the opposite of what the blank-proxy path used to do.
     */
    private fun finishSegment() {
        encoder.cutAtWaqf(waqfId)
        audio = FloatArray(0)
    }

    private fun appendAudio(chunk: FloatArray) {
        totalSamples += chunk.size
        val merged = FloatArray(audio.size + chunk.size)
        System.arraycopy(audio, 0, merged, 0, audio.size)
        System.arraycopy(chunk, 0, merged, audio.size, chunk.size)
        audio = if (merged.size > PipelineConfig.MAX_BUFFER_SAMPLES) {
            merged.copyOfRange(merged.size - PipelineConfig.MAX_BUFFER_SAMPLES, merged.size)
        } else {
            merged
        }
    }

    companion object {
        fun create(
            encoder: StreamingZipformerEncoder,
            finder: Finder,
            waqfId: Int,
            startSura: Int,
            startAya: Int,
            offset: Int = 1,
            attachLawasiq: Boolean = true,
            correctorCallIntervalSamples: Int = PipelineConfig.EXPERIMENTAL_CORRECTOR_CALL_INTERVAL_SAMPLES,
        ): ExperimentalCtcOnlyPipelineSession {
            val cursor = finder.verseToCursor(startSura to startAya)
            val corrector = Corrector(finder, cursor = cursor, attachLawasiq = attachLawasiq, offset = offset)
            return ExperimentalCtcOnlyPipelineSession(
                encoder,
                corrector,
                helpTilawa = (offset == 2),
                waqfId = waqfId,
                correctorCallIntervalSamples = correctorCallIntervalSamples,
            )
        }
    }
}
