package com.tarteelwarsh.tilawa.mushaf

import android.util.Log
import com.tarteelwarsh.tilawa.pipeline.PipelineResult

/**
 * "وضع التلاوة" (talawa, strict) vs "وضع المراجعة" (muraja3a, revue -
 * DEFAUT) - miroir de `recitationMode` (Dani_app/web/record.js) : en
 * muraja3a les erreurs de prononciation sont laissees passer (colorees comme
 * correctes, le texte se revele quand meme normalement) ; un saut de verset
 * "bypasse" reste toujours rouge dans les deux modes - ce n'est pas un
 * jugement de prononciation mais un fait structurel (le recitant a
 * effectivement saute ce passage).
 */
enum class RecitationMode { TALAWA, MURAJAA }

/** Etat d'un caractere une fois juge - miroir de letterColors[i]/letterProvisional[i]/letterBypassed[i] (record.js). null (non present dans la map) = jamais juge. */
private data class LetterState(val color: Int, val provisional: Boolean, var bypassed: Boolean)

private class MutableSpan(var start: Int, var end: Int) // [start, end) demi-ouvert, mute en place (miroir des objets {start,end} de record.js)

/** Compteurs bruts pour l'ecran de resume de session - voir RevealState.counts(). */
data class RevealCounts(val correct: Int, val duration: Int, val error: Int) {
    val total: Int get() = correct + duration + error
}

/**
 * Port fidele du moteur de reveal/masquage de Dani_app/web/record.js
 * (letterColors/revealedUpTo/bypassRanges/activeRunRange + `renderResult()`,
 * lignes 100-163 et 425-609) - PAS reinvente a partir des captures d'ecran :
 * chaque regle ci-dessous correspond a un commentaire source precis. Pure
 * logique d'etat, independante de Compose - lue ensuite par `MushafPaint`
 * pour peindre une page.
 *
 * Deux ecarts DELIBERES vs record.js, tous deux parce que ce port n'a
 * structurellement pas le probleme qu'ils resolvent (une seule source de
 * verite `Finder`, pas deux - `PipelineResult.offset` est toujours
 * l'authoritative offset, jamais de repli "chercher le texte") :
 * - pas de repli "recherche par contenu" quand `result.offset` est absent
 *   (toujours present ici, type non-nullable) ;
 * - le controle de parite offset<->texte (record.js compare `atOffset` au
 *   texte recu, pour detecter une derive entre deux one_string_uthmani
 *   independants) est garde en defense (log + ignore, pas de crash) plutot
 *   qu'en boucle critique, puisqu'il ne peut structurellement jamais se
 *   declencher ici (meme Finder des deux cotes).
 *
 * Pas porte, absent de PipelineResult (outil de dev web uniquement) :
 * `result.debug` / le mode debug (fenetre de recherche du corrector
 * surlignee sur le texte).
 */
class RevealState(
    private val oneStringUthmani: String,
    private val basmalaIndex: BasmalaIndex,
    var recitationMode: RecitationMode = RecitationMode.MURAJAA,
) {
    companion object {
        private const val TAG = "RevealState"

        // bypassMaxGapChars/MIN_BYPASS_TRUST : miroir exact de record.js (lignes 28-44).
        private fun bypassMaxGapChars(trust: Double): Double = 300.0 * trust
        private const val MIN_BYPASS_TRUST = 3.0
    }

    private val length = oneStringUthmani.length
    private val letters = arrayOfNulls<LetterState>(length) // letterColors/letterProvisional/letterBypassed fusionnes

    var revealedUpTo: Int = 0
        private set
    private var lastOffsetEnd = 0
    private var hasRecitedInSession = false
    private var activeRun: MutableSpan? = null
    private val bypassRanges = mutableListOf<MutableSpan>()

    fun colorAt(i: Int): Int? = letters[i]?.color
    fun isProvisionalAt(i: Int): Boolean = letters[i]?.provisional ?: false

    /**
     * Compteurs BRUTS (nombre de caracteres juges par code de couleur) pour
     * l'ecran "session_summary" - decision explicite : legende avec les
     * compteurs bruts uniquement, PAS le pourcentage/etiquette qualitative
     * ("85% - ممتاز") ni la ventilation par regle de tajwid du mockup Stitch
     * (`session_summary/code.html`) - ces deux-la exigeraient une
     * categorisation d'erreur (par regle) que `Corrector.correct()` ne
     * produit pas (seulement 1/0/-1/2 par caractere, voir ColorMapping).
     * Compte des CARACTERES, pas des versets (le mockup affiche "آية") - le
     * modele de donnees ne juge qu'au caractere, agreger par verset
     * inventerait un seuil de reussite qui n'existe nulle part cote
     * Dani_app ; documente aussi dans app/README.md.
     */
    fun counts(): RevealCounts {
        var correct = 0
        var duration = 0
        var error = 0
        for (letter in letters) {
            when (letter?.color) {
                1 -> correct++
                0 -> duration++
                -1 -> error++
            }
        }
        return RevealCounts(correct = correct, duration = duration, error = error)
    }

    /** true si la position `i` est masquee (pas encore recitee/jugee, et pas de mode "peek"). */
    fun isMasked(i: Int, peekMode: Boolean): Boolean = letters[i] == null && i >= revealedUpTo && !peekMode

    /**
     * Reinitialise TOUT l'etat - a appeler au demarrage d'une NOUVELLE prise
     * (nouvel enregistrement), jamais entre deux resultats de la meme
     * session. Miroir de la remise a zero faite par `startRecording()` cote
     * JS (letterColors/etc. re-alloues), PAS de `initRecitationState()`
     * (allocation one-shot, deja couverte par le constructeur ici).
     */
    fun resetSession() {
        letters.fill(null)
        revealedUpTo = 0
        lastOffsetEnd = 0
        hasRecitedInSession = false
        activeRun = null
        bypassRanges.clear()
    }

    /**
     * Port fidele de `renderResult(result)` (record.js:425-609). Retourne
     * `true` si l'etat de reveal a reellement change (repaint necessaire),
     * `false` sinon (resultat repete ou sans contenu juge).
     */
    fun applyResult(result: PipelineResult, suppressRepeats: Boolean = false): Boolean {
        // SUPPRESS_REPEATS && result.repeat -> ne rien repeindre (record.js:436-439).
        // Valeur par defaut alignee sur l'etat REEL actuel de record.js
        // (SUPPRESS_REPEATS = false, experience en cours - voir son
        // commentaire "flip back to true to restore it") plutot que sur son
        // comportement "nominal" documente mais actuellement desactive.
        if (suppressRepeats && result.repeat) return false

        val offset = result.offset
        if (offset < 0 || offset + result.text.length > length) {
            Log.e(TAG, "offset hors bornes (${offset}, len=${result.text.length}) - resultat ignore")
            return false
        }
        val atOffset = oneStringUthmani.substring(offset, offset + result.text.length)
        if (atOffset != result.text) {
            // Defense seulement (voir KDoc de classe) : ne peut structurellement
            // pas se produire ici (une seule source Finder), contrairement a
            // record.js ou deux one_string_uthmani independants peuvent deriver.
            Log.e(TAG, "parite offset<->texte rompue a $offset - resultat ignore (bug reel si ceci se declenche)")
            return false
        }

        // Le curseur retourne par le corrector peut deborder au-dela de ce
        // qui a ete reellement recite (frontieres verset/mot) - ces
        // caracteres portent le code 2 ("pas encore juge"). record.js:495-500.
        var judgedLength = result.colors.size
        while (judgedLength > 0 && result.colors[judgedLength - 1] == 2) judgedLength--
        if (judgedLength == 0) return false

        // Reassigne (pas un max) : un retour en arriere reel du curseur doit
        // re-masquer ce qui etait entre l'ancienne et la nouvelle position si
        // jamais juge individuellement. record.js:502-508.
        revealedUpTo = offset

        // Retrait des marques "bypass" sur lesquelles le recitant est revenu -
        // record.js:510-527.
        val bypassIterator = bypassRanges.iterator()
        while (bypassIterator.hasNext()) {
            val range = bypassIterator.next()
            if (range.end > offset) {
                for (i in maxOf(range.start, offset) until range.end) {
                    val letter = letters[i]
                    if (letter != null && letter.bypassed) letters[i] = null
                }
                range.end = minOf(range.end, offset)
            }
            if (range.start >= range.end) bypassIterator.remove()
        }

        // Texte saute entre la fin du resultat precedent et le debut de
        // celui-ci = un verset/passage "bypasse", marque comme erreur (sauf
        // separateurs et basmala sautee, un choix legitime du recitant) -
        // record.js:529-556. Garde par hasRecitedInSession : le tout premier
        // resultat d'une session ne peut pas etre un bypass (lastOffsetEnd
        // est encore une valeur d'une prise anterieure, ou 0).
        if (
            hasRecitedInSession &&
            offset > lastOffsetEnd &&
            result.trust >= MIN_BYPASS_TRUST &&
            offset - lastOffsetEnd < bypassMaxGapChars(result.trust)
        ) {
            for (i in lastOffsetEnd until offset) {
                if (oneStringUthmani[i] == ' ') continue
                if (basmalaIndex.isBasmalaOffset(i)) continue
                letters[i] = LetterState(color = -1, provisional = false, bypassed = true)
            }
            bypassRanges.add(MutableSpan(lastOffsetEnd, offset))
        }
        hasRecitedInSession = true

        val newRangeStart = offset
        val newRangeEnd = offset + judgedLength

        // rangesOverlap(a,b) = a.start<=b.end && b.start<=a.end (record.js:305-307)
        // - deux bornes INCLUSIVES malgre les plages [start,end) demi-ouvertes
        // utilisees partout ailleurs : deux plages simplement ADJACENTES
        // (a.end == b.start) comptent donc comme "en chevauchement" ici,
        // reproduit tel quel (pas une inclusive/exclusive au choix).
        val current = activeRun
        if (current != null && !(newRangeStart <= current.end && current.start <= newRangeEnd)) {
            // Pas de chevauchement : le corrector a saute ailleurs, la
            // supposition ouverte precedente est abandonnee EN ENTIER (pas
            // seulement sa partie provisoire - clearRange, record.js:309-315,
            // efface inconditionnellement, y compris les caracteres deja
            // finalises de cette supposition abandonnee). record.js:561-563.
            clearRun(current)
            activeRun = MutableSpan(newRangeStart, newRangeEnd)
        } else if (current != null) {
            // Chevauchement : le corrector affine sa supposition en cours -
            // si elle retreat (newRangeEnd < current.end), les caracteres
            // provisoires au-dela sont re-masques (mais pas les finalises).
            // record.js:564-580.
            for (i in newRangeEnd until current.end) {
                val letter = letters[i]
                if (letter != null && letter.provisional) letters[i] = null
            }
            current.start = minOf(current.start, newRangeStart)
            current.end = newRangeEnd
        } else {
            activeRun = MutableSpan(newRangeStart, newRangeEnd)
        }

        // The shadow goes when the utterance is over - by a committed waqf
        // (wasl=false) OR by a trailing waqf saying the reciter simply stopped.
        // Without the second case the last phrase stayed shadowed indefinitely,
        // because the model never emits a final "s" for it to be cut on.
        val finalised = !result.wasl || result.utteranceEnded
        val provisional = !finalised

        for (i in 0 until judgedLength) {
            if (result.colors[i] == 2) continue // "pas encore juge" incruste - laisse tel quel. record.js:586.
            // Mode "muraja3a" (revue, defaut) : les erreurs de prononciation
            // sont laissees passer (colorees correctes) - un bypass reste
            // rouge inconditionnellement (deja ecrit ci-dessus par le bloc
            // bypass, qui leve `bypassed`; cette boucle l'ecrase seulement si
            // le corrector a reellement juge cette position). record.js:591.
            val color = if (recitationMode == RecitationMode.MURAJAA) 1 else result.colors[i]
            letters[offset + i] = LetterState(color = color, provisional = provisional, bypassed = false)
        }

        lastOffsetEnd = newRangeEnd
        if (finalised) {
            // A committed waqf ends the utterance, so finalise EVERY letter of the
            // run now - not just the slice this result happened to judge.
            //
            // Each result only writes `provisional = result.wasl` over
            // [offset, offset+judgedLength). Earlier letters of the SAME utterance
            // lie outside that window, so before this they kept their provisional
            // shadow until some later result happened to cover them again - i.e.
            // the shadow on what you already recited lingered until you started
            // the next phrase. The waqf is the moment we know they are final, so
            // clear them here.
            val runStart = activeRun?.start ?: newRangeStart
            for (i in runStart until newRangeEnd) {
                val letter = letters[i]
                if (letter != null && letter.provisional) {
                    letters[i] = letter.copy(provisional = false)
                }
            }
            activeRun = null // plus une supposition ouverte. record.js:598-600.
        }

        return true
    }

    /**
     * Drop the provisional shadow on the open run - the model has marked a stop.
     *
     * Display only: no colour, cursor or trust is touched, and no text is
     * dropped. Separate from applyResult() because the stop is usually seen on a
     * chunk that produces NO correction at all (right after a waqf the remaining
     * text is too short to report), and the shadow has to clear then anyway.
     */
    fun finalizeProvisional(): Boolean {
        val run = activeRun ?: return false
        var changed = false
        for (i in run.start until run.end) {
            val letter = letters[i]
            if (letter != null && letter.provisional) {
                letters[i] = letter.copy(provisional = false)
                changed = true
            }
        }
        activeRun = null
        return changed
    }

    /** Miroir exact de clearRange (record.js:309-315) : efface INCONDITIONNELLEMENT, provisoire ou finalise. */
    private fun clearRun(run: MutableSpan) {
        for (i in run.start until run.end) letters[i] = null
    }
}
