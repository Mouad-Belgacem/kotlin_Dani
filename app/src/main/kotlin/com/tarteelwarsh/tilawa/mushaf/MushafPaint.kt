package com.tarteelwarsh.tilawa.mushaf

/** Etat visuel d'un caractere - miroir des classes CSS `unrevealed`/`state-*`/`provisional` de `paintRange()` (Dani_app/web/record.js:350-392). */
sealed interface PaintState {
    data object Unrevealed : PaintState
    /**
     * @param reached whether the reciter has ACTUALLY got here, independently of
     *   peek mode. In peek mode every letter on the page is revealed and so is
     *   painted Judged - which made the auto-scroll's "last judged letter" land on
     *   the last letter of the PAGE the moment the eye button was pressed, jumping
     *   to the bottom and freezing the follow. Rendering uses color/provisional;
     *   only the scroll follow reads this.
     */
    data class Judged(val color: Int, val provisional: Boolean, val reached: Boolean = true) : PaintState
}

data class PaintRun(val text: String, val state: PaintState)

/**
 * Port fidele de `paintRange()` (Dani_app/web/record.js:350-392) : regroupe
 * les caracteres consecutifs de meme etat visuel en un seul "run" - pas
 * seulement pour reduire le nombre de spans, mais parce qu'un span par
 * caractere casse les ligatures arabes (deja documente dans le source :
 * "الله"/"الرحمن" se retrouvent avec leurs lettres visuellement disjointes
 * sur certains moteurs de rendu, observe sur iPhone 13 mini) - le
 * regroupement l'evite par construction, meme risque potentiel sous
 * Compose/Skia. Le mode debug (surlignage de la fenetre de recherche du
 * corrector) n'est pas porte, voir RevealState.
 */
object MushafPaint {
    fun paintRange(revealState: RevealState, oneStringUthmani: String, from: Int, to: Int, peekMode: Boolean): List<PaintRun> {
        val runs = mutableListOf<PaintRun>()
        var runState: PaintState? = null
        val runText = StringBuilder()

        fun flush() {
            if (runText.isEmpty()) return
            runs.add(PaintRun(runText.toString(), runState!!))
            runText.clear()
        }

        for (i in from until to) {
            // Un espace (separateur mot/verset) n'est jamais masque et jamais
            // colore differemment - un espace cache est visuellement
            // identique a un espace visible, il n'y a rien a cacher.
            // record.js:366-371 (classe state-1).
            // Deliberately isMasked(.., peekMode = false): "has the reciter got
            // here", not "is it currently visible". See PaintState.Judged.reached.
            val reached = !revealState.isMasked(i, peekMode = false)
            val state: PaintState = when {
                oneStringUthmani[i] == ' ' -> PaintState.Judged(color = 1, provisional = false, reached = reached)
                revealState.isMasked(i, peekMode) -> PaintState.Unrevealed
                // letterColors[i] === null (jamais juge individuellement, mais
                // deja passe le curseur) rend comme state-1 (color: inherit) -
                // visuellement identique a state-2, d'ou le repli sur 1 ici.
                // record.js:372-383.
                else -> PaintState.Judged(color = revealState.colorAt(i) ?: 1, provisional = revealState.isProvisionalAt(i), reached = reached)
            }
            if (state != runState) {
                flush()
                runState = state
            }
            runText.append(oneStringUthmani[i])
        }
        flush()
        return runs
    }
}
