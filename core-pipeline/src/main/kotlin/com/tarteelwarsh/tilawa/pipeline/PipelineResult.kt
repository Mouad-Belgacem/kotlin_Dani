package com.tarteelwarsh.tilawa.pipeline

/**
 * Miroir du dict retourne par `process_chunk()` (Dani_app/api/pipeline.py) -
 * sans les champs de debug (`fragments`/`win`/`path`/`timings`) : c'etait de
 * l'introspection dev (patch temporaire de `finder.get_fragment`), sans
 * consommateur reel cote mobile.
 */
data class PipelineResult(
    val wasl: Boolean,
    /** Re-analyse d'un audio inchange : l'UI doit rafraichir les jauges/scores mais PAS repeindre les couleurs des lettres. */
    val repeat: Boolean,
    val sura: Int,
    val aya: Int,
    val text: String,
    /** Debut de `text` dans `Finder.oneStringUthmani` - a utiliser tel quel (jamais rechercher `text` par contenu, cf. Dani_app/api/pipeline.py). */
    val offset: Int,
    val colors: List<Int>,
    /** 0-6, confiance courante du Corrector. */
    val trust: Double,
    val cursor: Int,
    val prevCursor: Int,
    val settled: Boolean,
    /**
     * The model has marked a stop, so the text reported is final FOR DISPLAY and
     * the provisional shadow should be dropped.
     *
     * Deliberately separate from `wasl`: `wasl=false` also changes what the
     * Corrector does - it paints the unmatched tail of the search window as
     * MISTAKES rather than "not yet reached", and feeds the stops counter. A
     * trailing waqf is only evidence that the reciter went quiet, which is not
     * enough to start calling the rest of the window wrong. So this finalises the
     * rendering and nothing else.
     */
    val utteranceEnded: Boolean = false,
)
