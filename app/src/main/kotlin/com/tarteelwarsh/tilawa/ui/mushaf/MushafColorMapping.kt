package com.tarteelwarsh.tilawa.ui.mushaf

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.tarteelwarsh.tilawa.ui.theme.TilawaColors

/**
 * Mapping couleur specifique a l'ecran mushaf (reveal/pagination) - PAS le
 * meme mapping que `ui.livecorrection.ColorMapping` (carte compacte,
 * mockup `live_correction/code.html`). Extrait du VRAI CSS de Dani_app
 * (`Dani_app/web/style.css`, regles `.state-1`/`.state-0`/`.state--1`,
 * palette `[data-theme="dark"]`), pas du mockup Stitch - deviation
 * DELIBEREE et documentee par rapport a `ColorMapping.colorForCorrectionCode` :
 *
 *   1  correct                     -> `.state-1 { color: inherit }`   -> OnBackground (PAS vert : le mockup
 *   2  pas encore juge (embryon)   -> `.state-2 { color: inherit }`   -> OnBackground    live_correction colore
 *                                                                         "correct" en vert, le vrai Dani_app
 *                                                                         ne distingue PAS visuellement le texte
 *                                                                         correct du texte neutre - seules les
 *                                                                         deviations (erreur/duree) sont teintees)
 *  -1  erreur                      -> `.state--1` (--wrong, #e0705a)  -> Error, souligne
 *   0  madd/ghunna insuffisant     -> `.state-0` (--uncertain, #dcaa4c) -> FeedbackDuration, souligne
 *
 * `--wrong`/`--uncertain` (theme sombre, style.css) sont des couleurs
 * choisies independamment de la palette Stitch/DESIGN.md - le ROLE
 * semantique (erreur/incertain) est porte ici par les tokens Material deja
 * actes (`Error`/`FeedbackDuration`) plutot que de reimporter une deuxieme
 * palette parallele.
 *
 * `MushafRunColors` resout les 3 couleurs impliquees UNE FOIS, dans un
 * contexte `@Composable` (`TilawaColors.X` est reactif au theme depuis son
 * passage a `LocalTilawaPalette`, voir Theme.kt) - `mushafColorForCode()`
 * lui-meme reste une fonction PURE (pas `@Composable`), appelable depuis
 * `buildAnnotatedString { }` (un DSL de construction, pas un arbre Compose).
 */
data class MushafRunColors(val error: Color, val duration: Color, val neutral: Color)

@Composable
fun rememberMushafRunColors(): MushafRunColors = MushafRunColors(
    error = TilawaColors.Error,
    duration = TilawaColors.FeedbackDuration,
    neutral = TilawaColors.OnBackground,
)

fun mushafColorForCode(code: Int, colors: MushafRunColors): Color = when (code) {
    -1 -> colors.error
    0 -> colors.duration
    else -> colors.neutral // 1 (correct) et 2 (pas encore juge) - `.state-1`/`.state-2 { color: inherit }`
}

fun isMushafUnderlinedCode(code: Int): Boolean = code == -1 || code == 0
