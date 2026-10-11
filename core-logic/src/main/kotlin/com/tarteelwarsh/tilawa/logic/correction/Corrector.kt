package com.tarteelwarsh.tilawa.logic.correction

import com.tarteelwarsh.tilawa.logic.phonetizer.phonetize
import kotlin.math.exp

// Port fidele de Dani_app/tilawa_correction/Correction.py::Corrector (+ la
// fonction module-level dscore). Deux ecarts DELIBERES par rapport au
// Python, tous deux documentes en commentaire a l'endroit concerne :
// - le constructeur Python `finder=None -> finder == Finder()` est un bug de
//   frappe (== au lieu de =) qui laisse self.finder=None si jamais atteint -
//   jamais declenche en pratique (tous les appels reels passent un Finder
//   explicite). Ici `finder` est simplement un parametre requis, pas nullable.
// - `get_color()` (Correction.py) est du code mort : jamais appele, la ligne
//   active dans correct() est `colors += [-1] * (ai-a0)`, pas
//   `self.get_color(...)`. Non porte.
// __call__ (colorisation ANSI pour affichage terminal/notebook) n'est pas
// portee non plus - aucun consommateur reel dans le pipeline (api/pipeline.py
// appelle corrector.correct() directement et construit son propre payload a
// partir de uth_colors), et sans objet sur Android (rendu UI, pas terminal).

/** Miroir de Correction.py::dscore (module-level).
 *
 * The positive gain is 0.6, not 0.3: the server's Correction.py was changed
 * after this port was first written. Trust therefore ramps twice as fast on a
 * good match, while a bad one still only costs 0.2 - the asymmetry that lets an
 * occasional wrong provisional reading not suppress the ramp. */
fun dscore(x: Double, px: Double): Double {
    val effectiveX = if (px == x) 0.0 else x
    return if (effectiveX > 0) 0.6 * effectiveX else 0.2 * effectiveX
}

data class CorrectionResult(val uthmaniText: String, val colors: List<Int>, val additions: List<Int>)

class Corrector(
    val finder: Finder,
    var cursor: Int = 0,
    // Stocke pour parite d'API avec Correction.py, mais - fidelement -
    // JAMAIS lu par correct() : celui-ci utilise son propre parametre
    // `attachLawasiq` (defaut false), pas ce champ. Verifie dans le Python
    // source : self.attach_lawasiq n'apparait nulle part dans le corps de
    // correct(). Consequence reelle en production : attach_lawasiq vaut
    // toujours false dans phonetize() cote Corrector, quoi que le
    // constructeur ait recu (Dani_app/api/pipeline.py passe
    // attach_lawasiq=config.ATTACH au constructeur mais n'appelle jamais
    // correct(..., attach_lawasiq=...) explicitement).
    val attachLawasiq: Boolean = false,
    val offset: Int = 1,
) {
    var refCursor: Int = 0
    var settled: Boolean = false
    var stops: Int = 0
    // 2.0, not 0.0 - Correction.py:881 `self.score = 2`. A session now starts
    // part-way up the trust ramp, so `settled` arrives sooner and the search
    // window w = (10*exp(-score) + offset) * width starts far narrower.
    var score: Double = 2.0
    var previousScore: Double = 0.0
    var localScore: Double = 0.0
    var previousTilawa: String = ""

    /**
     * "colors" (par caractere phonetique) : 1=correct, 0=madd/ghunna insuffisant,
     * -1=erreur, 2=pas encore atteint (wasl). "additions" : positions (dans
     * uthmaniText, via track) ou l'ASR a produit du contenu que la reference
     * n'a pas.
     */
    fun correct(tilawa: String, wasl: Boolean = false, attachLawasiq: Boolean = false, threshold: Double = 3.0): CorrectionResult {
        val width = (finder.widthFactor * (tilawa.length + 5)).toInt()

        val w = ((10 * exp(-score) + offset) * width).toInt()
        var (start, end) = finder.refinedUthmani(tilawa, cursor, w, refCursor, pushToVerse = false)
        var uthmaniText = finder.oneStringUthmani.substring(start, end + 1).trim()
        val phonetized = phonetize(uthmaniText, attachLawasiq = attachLawasiq)
        var phoneticScript = phonetized.phonetic
        var track = phonetized.track
        val sm = ModifiedMatcher(phoneticScript, tilawa)

        // Gate is now `!settled` alone, with the wasl/length test replaced by the
        // runner-up GAP from get_aya: re-anchor only when one aya stands clearly
        // above the rest (gap >= 0.2), or when there is simply a lot of text to
        // go on (>= 40 chars). Correction.py:914-916.
        if (!settled) {
            val cursorRatio = sm.ratio(boarderPenalty = true)
            val match = finder.getAya(tilawa)
            if (match.gap >= 0.2 || tilawa.length >= 40) {
                val cursorT = finder.verseToCursor(match.aya)
                val (startT, endT) = finder.refinedUthmani(tilawa, cursorT, width)
                val uthmaniTextT = finder.oneStringUthmani.substring(startT, endT + 1).trim()
                val phonetizedT = phonetize(uthmaniTextT, attachLawasiq = attachLawasiq)
                sm.setSeq1(phonetizedT.phonetic)
                val globalScore = sm.ratio(boarderPenalty = true)

                if (cursorRatio < globalScore) {
                    start = startT
                    end = endT
                    uthmaniText = uthmaniTextT
                    phoneticScript = phonetizedT.phonetic
                    track = phonetizedT.track
                } else {
                    sm.setSeq1(phoneticScript)
                }
            }
        }

        cursor = end
        val r = sm.ratio(boarderPenalty = true)
        if ((!wasl || tilawa.length > 40) && r > 0) {
            refCursor = end
        } else if (!settled || r < 0) {
            cursor = refCursor
        }
        // ORDER MATTERS: `increment` is computed BEFORE stops, because stops is
        // now gated on it. The earlier port updated stops first, mirroring the
        // older Python where no such gate existed.
        previousScore = score
        val increment = dscore(r, localScore)
        score = minOf(2 * threshold, score + increment)
        score = maxOf(0.0, score)

        // Only a MEANINGFUL improvement counts as having reached a stop. Without
        // the gate, every chunk that merely re-analysed unchanged audio bumped
        // `stops`, so `stops >= 3` was reached far too easily.
        if (!wasl && increment > 0.1) {
            stops += 1
        } else if (increment > 0.1) {
            stops += maxOf(0, tilawa.length / 70 - previousTilawa.length / 70)
        }

        // `else if`, matching Correction.py:941-944 again. The separate-`if`
        // variant (which could un-set what the first branch set) was a deliberate
        // local divergence; the current Python makes a high score sufficient on
        // its own, so `stops` only matters while the score is still below
        // threshold. No setSeq1 here either - the Python does not do it, and sm
        // already holds the right sequence from the block above.
        if (score >= threshold) {
            settled = true
        } else if (stops < 3) {
            settled = false
        }

        if (r < 0) {
            return CorrectionResult("", emptyList(), emptyList())
        }

        // `r` reused directly, not recomputed - Correction.py:948 `self.local_score = r`.
        localScore = r

        val colors = ArrayList<Int>()
        val additions = ArrayList<Int>()
        var a0 = 0
        var b0 = 0
        var lastAi = 0
        var lastSizeA = 0
        for (block in sm.getMatchingBlocks()) {
            val ai = block.a
            val bj = block.b
            val sizea = block.sizeA
            val sizeb = block.sizeB
            if (a0 < ai) {
                repeat(ai - a0) { colors.add(-1) }
            }
            if (b0 < bj) {
                additions.add(track[a0])
            }
            val repA = runLengths(sm.a.substring(ai, ai + sizea))
            val repB = runLengths(sm.b.substring(bj, bj + sizeb))
            for (ind in repA.indices) {
                val c = if (repA[ind] <= repB[ind]) 1 else 0
                repeat(repA[ind]) { colors.add(c) }
            }
            a0 = ai + sizea
            b0 = bj + sizeb
            lastAi = ai
            lastSizeA = sizea
        }
        if (lastAi + lastSizeA != phoneticScript.length) {
            val fillColor = if (wasl) 2 else -1
            repeat(phoneticScript.length - lastAi - lastSizeA) { colors.add(fillColor) }
        }

        // Report des couleurs phonetiques (par caractere du script phonetique)
        // vers l'uthmani (par caractere du texte affiche) via track - min() :
        // si plusieurs positions phonetiques (ex. un madd etale sur N
        // caracteres) pointent vers le meme caractere uthmani, on garde la
        // PIRE (min) des couleurs.
        val uthColors = MutableList(uthmaniText.length) { 3 }
        for (i in colors.indices) {
            val pos = track[i]
            uthColors[pos] = minOf(uthColors[pos], colors[i])
        }

        // Comble les positions "3" (jamais touchees - lettres disparues en
        // phase phonetique) avec le max des couleurs voisines, pour ne pas
        // laisser de trou visuel non colore.
        var idx = 0
        while (idx < uthColors.size) {
            val value = uthColors[idx]
            var nextIdx = idx
            while (nextIdx < uthColors.size && uthColors[nextIdx] == value) nextIdx++
            if (value == 3) {
                val previous = if (idx == 0) -1 else uthColors[idx - 1]
                val next = if (nextIdx == uthColors.size) -1 else uthColors[nextIdx]
                val fill = maxOf(previous, next)
                for (k in idx until nextIdx) uthColors[k] = fill
            }
            idx = nextIdx
        }

        previousTilawa = tilawa
        return CorrectionResult(uthmaniText, uthColors, additions)
    }

    /**
     * Deliberately a Pair, though Finder.getAya now yields a third value (gap).
     *
     * Correction.py::Corrector.get_aya returns 2 values when settled but forwards
     * the finder's 3 when not - and api/pipeline.py:369 unpacks exactly 2, so the
     * server raises "too many values to unpack" on any unsettled correction. Only
     * (aya, score) is ever consumed, so that is what this returns in both cases.
     */
    fun getAya(tilawa: String): Pair<Pair<Int, Int>, Double> {
        if (settled) {
            return finder.ayaAtUthmani(cursor) to score
        }
        val match = finder.getAya(tilawa)
        return match.aya to match.ratio
    }
}

private fun runLengths(s: String): List<Int> {
    val lengths = ArrayList<Int>()
    var i = 0
    while (i < s.length) {
        var j = i + 1
        while (j < s.length && s[j] == s[i]) j++
        lengths.add(j - i)
        i = j
    }
    return lengths
}
