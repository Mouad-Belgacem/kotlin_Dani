package com.tarteelwarsh.tilawa.asr

import com.tarteelwarsh.tilawa.logic.tokenizer.RunAwareTokenizer

/**
 * Port fidele de StreamingCTCDecoder (scripts/streaming_pipeline_standard.py) :
 * collapse CTC AVEC etat inter-chunks (prev_label jamais reinitialise entre
 * deux appels a push()) - sans ça, deux occurrences consecutives d'un meme
 * symbole a cheval sur deux chunks seraient comptees comme deux occurrences
 * distinctes au lieu d'une seule fusionnee (voir core-asr/README.md).
 */
class StreamingCtcDecoder(private val tokenizer: RunAwareTokenizer, private val blankId: Int) {
    private var prevLabel: Int? = null
    private val tokenIds = mutableListOf<Int>()

    /**
     * Collapsed tokens from the SPECULATIVE tail - the provisional reading of
     * audio that has not filled a committed block yet. Rebuilt from scratch on
     * every push (never appended to), and never allowed to touch `tokenIds` or
     * `prevLabel`: last chunk's guess about this audio is thrown away, not
     * accumulated. Mirrors Dani_app's ZipformerStream.spec_ids.
     */
    private val specTokens = mutableListOf<Int>()


    /** ids argmax d'un chunk (deja tronque a outLen, un id par frame). */
    fun push(predIds: List<Int>) {
        for (p in predIds) {
            if (p != prevLabel && p != blankId) {
                tokenIds.add(p)
            }
            prevLabel = p
        }
    }

    /** Committed + provisional, in order - what the user should see. */
    fun text(): String = tokenizer.decode(tokenIds + specTokens)

    /** Committed only, for anything that must not act on a provisional reading. */
    fun committedText(): String = tokenizer.decode(tokenIds)

    fun clearSpeculative() = specTokens.clear()

    /**
     * Collapse a speculative window's raw ids, continuing from the COMMITTED
     * prevLabel so a symbol straddling the join is merged rather than doubled -
     * but without writing prevLabel back, so the committed stream is unaffected.
     */
    fun pushSpeculative(predIds: List<Int>) {
        specTokens.clear()
        var prev = prevLabel
        for (p in predIds) {
            if (p != prev && p != blankId) specTokens.add(p)
            prev = p
        }
    }

    /** Copie des ids collapsed accumules jusqu'ici (pour comparaison exacte, sans repasser par un re-encodage ambigu du texte). */
    fun tokenIds(): List<Int> = tokenIds.toList()

    // --- waqf segmentation -------------------------------------------------
    // The waqf-aware checkpoint predicts a dedicated symbol where the reciter
    // stops, so segment boundaries now come from the MODEL rather than from an
    // acoustic VAD. It is now the ONLY thing that ends a phrase.
    //
    // Operating on COLLAPSED tokens is equivalent to the Python implementation,
    // which trims raw per-frame ids through the whole waqf run:
    //   raw       [A A s s B B] -> trim -> [B B] -> collapse -> "B"
    //   collapsed [A, s, B]     -> drop through s          -> [B]
    // Same result, and collapsed is what this decoder already keeps.

    /** Index of the first waqf token over committed+speculative, or -1. */
    fun firstWaqfIndex(waqfId: Int): Int {
        val i = tokenIds.indexOf(waqfId)
        if (i >= 0) return i
        val j = specTokens.indexOf(waqfId)
        return if (j < 0) -1 else tokenIds.size + j
    }

    /**
     * The current utterance's text, up to but NOT including the first waqf, plus
     * whether a waqf is present. The caller feeds the text to the Corrector
     * either way, and may cut only when one was found.
     */
    fun segmentText(waqfId: Int): Segment {
        val all = tokenIds + specTokens
        val i = firstWaqfIndex(waqfId)
        if (i < 0) return Segment(tokenizer.decode(all), found = false, committed = false)

        // A COMMITTED waqf is authoritative: the utterance is everything before
        // it, and the caller ends it.
        if (i < tokenIds.size) {
            return Segment(tokenizer.decode(all.subList(0, i)), found = true, committed = true)
        }

        // A SPECULATIVE waqf gets a PROVISIONAL CUT - report what comes AFTER it.
        // Reporting the text BEFORE it (what this used to do) meant that the
        // moment the model predicted a pause, everything recited next was decoded
        // but invisible until a committed window absorbed that waqf: up to
        // rawChunkSize of audio later - 640ms at commit=64 - on every single
        // pause. Measured on a real recording, 8 characters appeared in one step
        // at the cut, having been suppressed for 400ms beforehand.
        //
        // Nothing is destroyed to do this: tokenIds is untouched and no cut
        // happens. If the waqf vanishes on the next push - specTokens is rebuilt
        // from scratch every time - this reverts by itself. Same contract as the
        // speculative tail, safe for the same reason: a provisional reading may be
        // wrong because it is REPLACED, never accumulated.
        //
        // Skip the whole waqf run rather than one token: collapse merges adjacent
        // duplicates only when prevLabel carries across the committed/speculative
        // join, so tokenIds can end with the waqf and specTokens begin with it.
        var j = i
        while (j < all.size && all[j] == waqfId) j++
        val after = tokenizer.decode(all.subList(j, all.size))

        // A provisional cut is only meaningful when the reciter has actually
        // RESUMED - i.e. there is real text after the waqf.
        //
        // A trailing waqf with nothing after it is a PHANTOM: the speculative
        // window is padded with LOG_EPS silence, and the encoder attends
        // bidirectionally within a block, so that padding bleeds into the last
        // real output frames and the model reads it as a stop. Cutting there
        // reports "" and discards the utterance in progress - seen on device as
        // `asr=0ch ctc=36ch`, with the corrector then re-anchoring onto a
        // completely unrelated sura.
        if (after.isEmpty()) {
            // trailing = true: a waqf with no text after it. Not worth CUTTING on
            // (it can vanish, and the phantom case would discard the utterance),
            // but it is the only evidence the app ever gets that the reciter has
            // STOPPED - the model was never trained to emit a final "s", so this
            // speculative one never commits. The UI uses it to drop the shadow.
            return Segment(
                tokenizer.decode(all.subList(0, i)),
                found = true, committed = false, trailing = true,
            )
        }
        return Segment(after, found = true, committed = false)
    }

    /** text up to the first waqf; `found` whether there is one; `committed`
     *  whether it is final and may therefore be cut on; `trailing` whether the
     *  waqf has NOTHING after it - i.e. the reciter has stopped rather than
     *  moved on to the next phrase. */
    data class Segment(
        val text: String,
        val found: Boolean,
        val committed: Boolean,
        val trailing: Boolean = false,
    )

    /**
     * Forget the finished utterance: drop collapsed tokens through the first
     * waqf. Returns false if there is none.
     *
     * Deliberately does NOT reset prevLabel, and the caller must NOT reset the
     * encoder. The cache, the fbank extractor and the read pointer all carry on,
     * so the encoder keeps its left context across the boundary - which is what
     * the model was trained on, since the waqf symbol appeared mid-clip with real
     * silence around it rather than at a freshly padded start. Only the decoded
     * tokens are forgotten.
     */
    fun cutAtWaqf(waqfId: Int): Boolean {
        // COMMITTED index only, deliberately not firstWaqfIndex(): a speculative
        // waqf is provisional and must never be cut on, and its index is beyond
        // tokenIds anyway.
        val i = tokenIds.indexOf(waqfId)
        if (i < 0) return false
        val kept = tokenIds.subList(i + 1, tokenIds.size).toList()
        tokenIds.clear()
        tokenIds.addAll(kept)
        return true
    }



    fun reset() {
        prevLabel = null
        tokenIds.clear()
        specTokens.clear()
    }
}
