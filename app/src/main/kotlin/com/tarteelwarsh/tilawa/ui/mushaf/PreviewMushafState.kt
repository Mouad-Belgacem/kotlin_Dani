package com.tarteelwarsh.tilawa.ui.mushaf

import com.tarteelwarsh.tilawa.mushaf.PaintRun
import com.tarteelwarsh.tilawa.mushaf.PaintState

// --- Etat statique pour validation visuelle (meme discipline que
// LiveCorrectionScreen.PreviewState : "rendu statique valide avant
// branchement micro reel") - Al-Fatiha, texte repris de
// stitch_tarteel_warsh_ai_teacher/revealed_mode/code.html (deja verifie
// Uthmani Warsh reel), numerotation d'ayat WARSH (la basmala fusionne avec
// l'ayah 1, pas une ayah separee - voir BasmalaIndex/app.js::buildBasmalaIndex).
// Motif de couleurs choisi pour exercer les 4 etats : correct (inherit,
// PAS vert - voir MushafColorMapping), duree insuffisante (ambre souligne),
// erreur (rouge souligne), et masque (pas encore recite).

private fun judgedRun(text: String, code: Int, provisional: Boolean = false) =
    PaintRun(text, PaintState.Judged(color = code, provisional = provisional))

private fun unrevealedRun(text: String) = PaintRun(text, PaintState.Unrevealed)

internal val PreviewMushafState = MushafUiState(
    isListening = true,
    page = MushafPageContent(
        pageNumber = 1,
        pageNumberLabel = "١",
        pageCount = 604,
        suraName = "سورة الفاتحة",
        juzLabel = "الجزء الأول",
        items = listOf(
            MushafPageItem.SuraHeader("سورة الفاتحة"),
            MushafPageItem.Basmala(listOf(judgedRun("بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ", 1))),
            MushafPageItem.Ayah(1, 1, "١", listOf(judgedRun("الْحَمْدُ لِلَّهِ رَبِّ الْعَالَمِينَ", 1))),
            MushafPageItem.Ayah(1, 2, "٢", listOf(judgedRun("الرَّحْمَٰنِ الرَّحِيمِ", 1))),
            MushafPageItem.Ayah(
                1, 3, "٣",
                listOf(
                    judgedRun("مَالِكِ ", 1),
                    judgedRun("يَوْمِ ", 0), // madd/ghunna insuffisant
                    judgedRun("الدِّينِ", 1),
                ),
            ),
            MushafPageItem.Ayah(1, 4, "٤", listOf(judgedRun("إِيَّاكَ نَعْبُدُ وَإِيَّاكَ نَسْتَعِينُ", 1, provisional = true))),
            MushafPageItem.Ayah(
                1, 5, "٥",
                listOf(
                    judgedRun("اهْدِنَا الصِّرَاطَ ", 1),
                    judgedRun("الْمُسْتَقِيمَ", -1), // erreur
                ),
            ),
            // Verset masque : exerce le "vrai" scenario reste jamais teste en
            // preview jusqu'ici - une sourate courte suivante s'ouvrant sur
            // la MEME page (bug reel trouve sur device : cadre/basmala
            // manquants pour la 2e sourate d'une page avant la correction de
            // buildPageContent). Scenario synthetique (page 1 reelle ne
            // contient qu'Al-Fatiha) - juste pour exercer visuellement le
            // chemin de code corrige, la verification reelle reste le
            // device (Al-Layl -> Ad-Duha observe en direct).
            MushafPageItem.Ayah(1, 6, "٦", listOf(unrevealedRun("صِرَاطَ الَّذِينَ أَنْعَمْتَ عَلَيْهِمْ غَيْرِ الْمَغْضُوبِ عَلَيْهِمْ"))),
            MushafPageItem.Ayah(1, 7, "٧", listOf(unrevealedRun("وَلَا الضَّالِّينَ"))),
            MushafPageItem.SuraHeader("سورة البقرة"),
            MushafPageItem.Basmala(listOf(judgedRun("بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ", 1))),
            MushafPageItem.Ayah(2, 1, "١", listOf(unrevealedRun("الٓمٓ"))),
        ),
    ),
    computeLatencyMs = 42.0,
    latencyMs = 118.0,
    backlogChunks = 0,
)
