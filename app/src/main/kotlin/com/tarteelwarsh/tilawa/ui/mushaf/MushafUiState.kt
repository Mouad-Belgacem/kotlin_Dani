package com.tarteelwarsh.tilawa.ui.mushaf

import com.tarteelwarsh.tilawa.mushaf.PaintRun
import com.tarteelwarsh.tilawa.mushaf.RecitationMode

/**
 * Un element de page, dans l'ordre DOM EXACT de `buildPageContent()`
 * (Dani_app/web/app.js) - une page peut contenir PLUSIEURS `SuraHeader`/
 * `Basmala` si plusieurs sourates courtes s'ouvrent sur la meme page (ex:
 * fin d'Al-Layl + debut d'Ad-Duha) : chaque segment de page est teste
 * individuellement (`seg.from == 1`), pas seulement le premier de la page -
 * bug reel trouve sur device (basmala/cadre manquants pour la 2e sourate
 * d'une page), corrige ici en reproduisant fidelement la boucle source.
 */
sealed interface MushafPageItem {
    data class SuraHeader(val text: String) : MushafPageItem
    data class Basmala(val runs: List<PaintRun>) : MushafPageItem

    /**
     * `runs` = SEULEMENT le texte de l'ayah, deja peint (voir MushafPaint) -
     * l'espace separateur qui suit (toujours visible, jamais colore/masque -
     * `repaintCurrentPage()` l'ajoute comme noeud de texte SEPARE, en dehors
     * de `paintRange()`) est ajoute par le composable, pas stocke ici.
     */
    data class Ayah(val sura: Int, val aya: Int, val numberLabel: String, val runs: List<PaintRun>) : MushafPageItem
}

/**
 * Contenu d'UNE page de mushaf (604 au total, pagination officielle Madinah
 * Warsh - voir core PageIndex) - miroir de ce que `buildPageContent()` +
 * `repaintCurrentPage()` construisent ensemble cote Dani_app
 * (Dani_app/web/app.js + record.js), fusionnes ici en un seul etat immutable
 * pret pour Compose plutot que des mutations DOM incrementales.
 */
data class MushafPageContent(
    val pageNumber: Int,
    val pageNumberLabel: String, // chiffres arabes-indiens - app.js::renderPage, pageNumberEl (absent de ce port jusqu'ici)
    val pageCount: Int,
    val suraName: String, // sourate du PREMIER segment de la page - app.js::renderPage, infoSurahEl
    val juzLabel: String, // "الجزء ..." - app.js::renderPage, infoJuzEl
    val items: List<MushafPageItem>,
)

data class MushafUiState(
    val page: MushafPageContent? = null,
    /** Pages either side, rendered ONLY during a swipe so the gap shows the real
     *  neighbouring page instead of the background. Rebuilt when the page number
     *  changes, not on every correction. */
    val prevPage: MushafPageContent? = null,
    val nextPage: MushafPageContent? = null,
    val isListening: Boolean = false,
    /** Non-null when starting capture failed (mic busy, permission, model load) -
     * shown in the UI so a dead record button is never silent. */
    val startError: String? = null,
    /** One-line self-diagnosis, shown while listening. Exists so a silent failure
     * can be read off the screen instead of needing adb: it distinguishes "the ASR
     * hears nothing" from "the ASR hears but the corrector returns nothing" from
     * "results exist but are not painted". */
    val debugLine: String? = null,
    val isLoadingModels: Boolean = false,
    // "Terminer la session" en cours (LiveCorrectionViewModel.endSession) -
    // vidage du backlog + flush() de l'encodeur + derniere correction, avant
    // de considerer la session close. true entre le clic et la transition
    // vers session_summary - sert a desactiver le bouton micro le temps que
    // ca se termine (double-clic pendant la finalisation, sinon confus).
    val isFinalizing: Boolean = false,
    val peekMode: Boolean = false, // "let me peek" - texte masque montre temporairement sans etre juge. record.js::peekMode
    val recitationMode: RecitationMode = RecitationMode.MURAJAA,
    // Confiance du Corrector (0-6, `PipelineResult.trust`) - miroir de
    // `#trust-meter`/`updateTrustMeter()` (index.html:73-77, record.js) :
    // affiche UNIQUEMENT pendant l'ecoute (`trustMeterEl.hidden = !recording`),
    // absent de ce port jusqu'ici alors que la donnee existait deja.
    val trust: Double? = null,
    val computeLatencyMs: Double? = null,
    val latencyMs: Double? = null,
    val backlogChunks: Int = 0,
    val resultId: Long = 0,
    val capturedAtNanos: Long? = null,
)
