@file:OptIn(ExperimentalTextApi::class)

package com.tarteelwarsh.tilawa.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tarteelwarsh.tilawa.R

// Manrope et JetBrains Mono sont des polices VARIABLES (un seul fichier,
// axe "wght") - google/fonts n'en publie pas d'instances statiques pour ces
// deux familles (verifie via l'API GitHub, pas suppose). Chaque poids est
// donc declare via FontVariation.Settings sur le MEME fichier, plutot que
// plusieurs fichiers - supporte nativement depuis Android 8.0 (API 26,
// exactement notre minSdk).
private fun manropeWeight(weight: FontWeight) = Font(
    R.font.manrope,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun jetBrainsMonoWeight(weight: FontWeight) = Font(
    R.font.jetbrains_mono,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val ManropeFamily = FontFamily(
    manropeWeight(FontWeight.Normal),
    manropeWeight(FontWeight.SemiBold),
    manropeWeight(FontWeight.Bold),
)

val JetBrainsMonoFamily = FontFamily(
    jetBrainsMonoWeight(FontWeight.Medium),
)

// Uthmani_warsh.ttf : copie exacte de Dani_app/web/fonts/UthmanicWarshV21.ttf
// (deja utilisee, lecture seule, par le web app existant) - remplace le
// fallback web du mockup (`local('Amiri Quran'), serif`, qui n'etait qu'un
// pis-aller Stitch faute d'acces a la vraie police de production).
val UthmaniWarshFamily = FontFamily(Font(R.font.uthmani_warsh, weight = FontWeight.Normal, style = FontStyle.Normal))

/**
 * Styles copies caractere pres de la section `typography:` du front-matter
 * DESIGN.md (px/poids/lineHeight). `letterSpacing` uniquement sur label-sm
 * (seule entree qui en declare une dans le YAML).
 */
object TilawaType {
    val QuranDisplay = TextStyle(
        fontFamily = UthmaniWarshFamily,
        fontSize = 34.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 56.sp,
    )
    val QuranText = TextStyle(
        fontFamily = UthmaniWarshFamily,
        fontSize = 24.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 48.sp,
    )
    val HeadlineLg = TextStyle(
        fontFamily = ManropeFamily,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 28.sp,
    )
    val HeadlineMd = TextStyle(
        fontFamily = ManropeFamily,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp,
    )
    val BodyLg = TextStyle(
        fontFamily = ManropeFamily,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp,
    )
    val LabelSm = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    )
}
