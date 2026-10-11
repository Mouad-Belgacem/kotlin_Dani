package com.tarteelwarsh.tilawa.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Un jeu complet de tokens Material 3 - une instance sombre (`TilawaDarkPalette`)
 * et une claire (`TilawaLightPalette`), voir plus bas. `TilawaColors` (objet
 * en bas de fichier) expose ces champs comme des proprietes `@Composable`
 * qui lisent `LocalTilawaPalette` - chaque site d'appel existant
 * (`TilawaColors.Background`, 122 occurrences dans l'app) continue de
 * compiler tel quel, mais devient reactif au theme choisi (voir Theme.kt)
 * au lieu d'etre fige sur le sombre.
 */
data class TilawaPalette(
    val surface: Color,
    val surfaceDim: Color,
    val surfaceBright: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val outline: Color,
    val outlineVariant: Color,
    val surfaceTint: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val primaryFixed: Color,
    val primaryFixedDim: Color,
    val onPrimaryFixed: Color,
    val onPrimaryFixedVariant: Color,
    val secondaryFixed: Color,
    val secondaryFixedDim: Color,
    val onSecondaryFixed: Color,
    val onSecondaryFixedVariant: Color,
    val tertiaryFixed: Color,
    val tertiaryFixedDim: Color,
    val onTertiaryFixed: Color,
    val onTertiaryFixedVariant: Color,
    val background: Color,
    val onBackground: Color,
    val surfaceVariant: Color,
    // Palette Tajweed - definie en dur dans live_correction/code.html (classes
    // .text-feedback-correct/.text-feedback-duration), absente du vrai
    // Dani_app (qui n'a pas de concept "correct = vert", voir
    // MushafColorMapping.kt) - meme valeur dans les deux themes, pas une
    // couleur a deriver de Dani_app.
    val feedbackCorrect: Color,
    val feedbackDuration: Color,
    /** Hairline ruled under each line of mushaf text. Must stay close to the
     *  background: it is a guide for the eye, not a visible border. */
    val mushafRule: Color,
    /** Accent for elements sitting ON the mushaf page - verse circles, the sura
     *  header frame, the sura/juz pill. Currently the SAME value as `primary`
     *  (one accent across the app). Kept as its own token so the page can be
     *  given a different accent from the chrome again without touching call
     *  sites - the monochrome experiment did exactly that. */
    val mushafAccent: Color,
)

// Tokens copies caractere pres du front-matter YAML de
// stitch_tarteel_warsh_ai_teacher/tarteel_warsh/DESIGN.md (section `colors:`)
// - jamais redevines a l'oeil sur le rendu du mockup.
val TilawaDarkPalette = TilawaPalette(
    surface = Color(0xFF000000), // true black - explicitly requested
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF38393A),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF1E2020),
    surfaceContainerHigh = Color(0xFF282A2B),
    surfaceContainerHighest = Color(0xFF333535),
    onSurface = Color(0xFFFFFFFF), // true white - explicitly requested
    onSurfaceVariant = Color(0xFFAEBCC5),
    inverseSurface = Color(0xFFE2E2E2),
    inverseOnSurface = Color(0xFF2F3131),
    outline = Color(0xFF7D8C96),
    outlineVariant = Color(0xFF39444B),
    surfaceTint = Color(0xFF9DB7C9),
    // Browner still. The web dark --accent (#D1A44E) is a light GOLD; this drops
    // saturation and lightness toward the web LIGHT --accent (#8A6D3B) without
    // going so dark that OnPrimary stops reading on it.
    primary = Color(0xFF9DB7C9),
    onPrimary = Color(0xFF0B1A22), // DARK ink: #9DB7C9 is a light accent, so white on it
                               // would be ~1.9:1 contrast. Dark gives ~8:1.
    primaryContainer = Color(0xFFBFD3E0),
    onPrimaryContainer = Color(0xFF08202C),
    inversePrimary = Color(0xFF3E5A6B),
    secondary = Color(0xFFC8C6C5),
    onSecondary = Color(0xFF313030),
    secondaryContainer = Color(0xFF474746),
    onSecondaryContainer = Color(0xFFB7B5B4),
    tertiary = Color(0xFFD0CDCD),
    onTertiary = Color(0xFF303030),
    tertiaryContainer = Color(0xFFB4B2B2),
    onTertiaryContainer = Color(0xFF454545),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    primaryFixed = Color(0xFFD6E5EF),
    primaryFixedDim = Color(0xFF9DB7C9),
    onPrimaryFixed = Color(0xFF081721),
    onPrimaryFixedVariant = Color(0xFF2C4756),
    secondaryFixed = Color(0xFFE5E2E1),
    secondaryFixedDim = Color(0xFFC8C6C5),
    onSecondaryFixed = Color(0xFF1C1B1B),
    onSecondaryFixedVariant = Color(0xFF474746),
    tertiaryFixed = Color(0xFFE4E2E1),
    tertiaryFixedDim = Color(0xFFC8C6C6),
    onTertiaryFixed = Color(0xFF1B1C1C),
    onTertiaryFixedVariant = Color(0xFF474747),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF333535),
    feedbackCorrect = Color(0xFF4ADE80),
    feedbackDuration = Color(0xFFFBBF24),
    // Bright enough to read against TRUE black. #161C20 vanished: it was tuned
    // against the old near-black background, and moving the rule onto the
    // baseline put the glyphs over it as well.
    mushafRule = Color(0xFF3B454D),
    mushafAccent = Color(0xFF9DB7C9),
)

// Theme clair - AUCUN mockup Stitch/DESIGN.md n'en definit un (tous
// `<html class="dark">`, deja documente comme "pas un oubli"). Le vrai
// Dani_app, lui, EN A un (Dani_app/web/style.css:10-28, `:root{}`, le
// theme PAR DEFAUT sur desktop) - source reelle utilisee ici plutot que
// d'inventer des valeurs :
//   --bg:#faf7f0 --sidebar-bg:#ffffff --page-bg:#fffdf6 --ink:#2b2620
//   --muted:#8a8070 --accent:#8a6d3b --border:#e8e0d0 --wrong:#c0392b
//   --uncertain:#b8860b
// Dani_app ne definit que ~13 roles semantiques ; Material 3 en exige
// davantage (containers, fixed variants, tertiary...). Simplification
// ASSUMEE : les roles que Dani_app ne couvre pas sont derives
// algorithmiquement par `lightColorScheme()` (tonal palette Material 3 a
// partir des roles fournis), pas choisis a la main - les roles ancres dans
// le vrai Dani_app (primary/background/surface/onBackground/onSurface/
// error/outline) restent, eux, exacts.
//
// Bug REEL trouve sur device : `lightColorScheme(primary=...)` sans
// `secondary`/`tertiary` explicites RETOMBE sur la palette "Baseline" violette
// par defaut de Material 3 pour CES roles precis (pas neutre - un vrai violet
// M3, `ColorLightTokens`) - `surfaceContainer`/`secondaryContainer`/etc.
// (pastille de riwaya, carte "تلاوة الخلفية", pastilles de theme non
// selectionnees) apparaissaient donc violettes, en rupture totale avec la
// palette chaude (creme/brun/or) du reste du theme clair. Corrige en
// choisissant ces roles a la main dans la MEME famille chaude/neutre que
// `TilawaDarkPalette` fait deja explicitement (voir plus haut) - pas de
// nouvel emprunt a `lightColorScheme()` pour eux.
private val derivedLightScheme = lightColorScheme(
    primary = Color(0xFF8A6D3B), // --accent
    background = Color(0xFFFAF7F0), // --bg
    onBackground = Color(0xFF2B2620), // --ink
    surface = Color(0xFFFFFFFF), // --sidebar-bg
    onSurface = Color(0xFF2B2620), // --ink
    error = Color(0xFFC0392B), // --wrong
    outline = Color(0xFF8A8070), // --muted
    outlineVariant = Color(0xFFE8E0D0), // --border
)

val TilawaLightPalette = TilawaPalette(
    surface = derivedLightScheme.surface,
    surfaceDim = derivedLightScheme.surfaceDim,
    surfaceBright = derivedLightScheme.surfaceBright,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFDF6), // --page-bg (ancre reelle - la carte de page mushaf)
    surfaceContainer = Color(0xFFF3EEE3), // chaud/neutre, entre --bg et --border - PAS le violet Baseline M3
    surfaceContainerHigh = Color(0xFFEDE6D6),
    surfaceContainerHighest = Color(0xFFE5DCC8),
    onSurface = derivedLightScheme.onSurface,
    onSurfaceVariant = derivedLightScheme.onSurfaceVariant,
    inverseSurface = derivedLightScheme.inverseSurface,
    inverseOnSurface = derivedLightScheme.inverseOnSurface,
    outline = derivedLightScheme.outline,
    outlineVariant = derivedLightScheme.outlineVariant,
    surfaceTint = derivedLightScheme.surfaceTint,
    primary = derivedLightScheme.primary,
    onPrimary = derivedLightScheme.onPrimary,
    primaryContainer = derivedLightScheme.primaryContainer,
    onPrimaryContainer = derivedLightScheme.onPrimaryContainer,
    inversePrimary = derivedLightScheme.inversePrimary,
    // Meme logique que secondary/tertiary de TilawaDarkPalette (gris chaud
    // neutre, PAS de teinte propre - Dani_app n'a pas d'accent secondaire) -
    // simplement inverse en luminosite pour un fond clair.
    secondary = Color(0xFF79726A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE6D6),
    onSecondaryContainer = Color(0xFF3D372E),
    tertiary = Color(0xFF6F6A5D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE5DCC8),
    onTertiaryContainer = Color(0xFF39362B),
    error = derivedLightScheme.error,
    onError = derivedLightScheme.onError,
    errorContainer = derivedLightScheme.errorContainer,
    onErrorContainer = derivedLightScheme.onErrorContainer,
    // "Fixed" roles : absents de `ColorScheme` dans le Material 3 de ce BOM
    // (androidx.compose.material3:1.3.0) - et de toute facon INUTILISES
    // ailleurs dans l'app (verifie, aucun site d'appel reel) - repli sur
    // les containers/roles les plus proches plutot que sur le schema genere.
    primaryFixed = derivedLightScheme.primaryContainer,
    primaryFixedDim = derivedLightScheme.primary,
    onPrimaryFixed = derivedLightScheme.onPrimaryContainer,
    onPrimaryFixedVariant = derivedLightScheme.onPrimaryContainer,
    secondaryFixed = Color(0xFFEDE6D6),
    secondaryFixedDim = Color(0xFF79726A),
    onSecondaryFixed = Color(0xFF3D372E),
    onSecondaryFixedVariant = Color(0xFF3D372E),
    tertiaryFixed = Color(0xFFE5DCC8),
    tertiaryFixedDim = Color(0xFF6F6A5D),
    onTertiaryFixed = Color(0xFF39362B),
    onTertiaryFixedVariant = Color(0xFF39362B),
    background = derivedLightScheme.background,
    onBackground = derivedLightScheme.onBackground,
    surfaceVariant = derivedLightScheme.surfaceVariant,
    feedbackCorrect = Color(0xFF16A34A), // meme role, assombri pour rester lisible sur fond clair
    feedbackDuration = Color(0xFFB8860B), // --uncertain
    mushafRule = Color(0xFFE0E4E2),
    mushafAccent = Color(0xFF5A5A5A),
)

/** Palette active - fournie par `TilawaWarshTheme` (Theme.kt) selon `AppearanceMode`. Repli sombre si utilise hors theme (previews notamment). */
val LocalTilawaPalette = staticCompositionLocalOf { TilawaDarkPalette }

/**
 * Meme API qu'avant (`TilawaColors.Background`, etc., 122 sites d'appel
 * inchanges dans l'app) mais chaque propriete lit desormais
 * `LocalTilawaPalette.current` - reactif au theme choisi au lieu d'etre
 * fige sur le sombre.
 */
object TilawaColors {
    val Surface: Color @Composable get() = LocalTilawaPalette.current.surface
    val SurfaceDim: Color @Composable get() = LocalTilawaPalette.current.surfaceDim
    val SurfaceBright: Color @Composable get() = LocalTilawaPalette.current.surfaceBright
    val SurfaceContainerLowest: Color @Composable get() = LocalTilawaPalette.current.surfaceContainerLowest
    val SurfaceContainerLow: Color @Composable get() = LocalTilawaPalette.current.surfaceContainerLow
    val SurfaceContainer: Color @Composable get() = LocalTilawaPalette.current.surfaceContainer
    val SurfaceContainerHigh: Color @Composable get() = LocalTilawaPalette.current.surfaceContainerHigh
    val SurfaceContainerHighest: Color @Composable get() = LocalTilawaPalette.current.surfaceContainerHighest
    val OnSurface: Color @Composable get() = LocalTilawaPalette.current.onSurface
    val OnSurfaceVariant: Color @Composable get() = LocalTilawaPalette.current.onSurfaceVariant
    val InverseSurface: Color @Composable get() = LocalTilawaPalette.current.inverseSurface
    val InverseOnSurface: Color @Composable get() = LocalTilawaPalette.current.inverseOnSurface
    val Outline: Color @Composable get() = LocalTilawaPalette.current.outline
    val OutlineVariant: Color @Composable get() = LocalTilawaPalette.current.outlineVariant
    val SurfaceTint: Color @Composable get() = LocalTilawaPalette.current.surfaceTint
    val Primary: Color @Composable get() = LocalTilawaPalette.current.primary
    val OnPrimary: Color @Composable get() = LocalTilawaPalette.current.onPrimary
    val PrimaryContainer: Color @Composable get() = LocalTilawaPalette.current.primaryContainer
    val OnPrimaryContainer: Color @Composable get() = LocalTilawaPalette.current.onPrimaryContainer
    val InversePrimary: Color @Composable get() = LocalTilawaPalette.current.inversePrimary
    val Secondary: Color @Composable get() = LocalTilawaPalette.current.secondary
    val OnSecondary: Color @Composable get() = LocalTilawaPalette.current.onSecondary
    val SecondaryContainer: Color @Composable get() = LocalTilawaPalette.current.secondaryContainer
    val OnSecondaryContainer: Color @Composable get() = LocalTilawaPalette.current.onSecondaryContainer
    val Tertiary: Color @Composable get() = LocalTilawaPalette.current.tertiary
    val OnTertiary: Color @Composable get() = LocalTilawaPalette.current.onTertiary
    val TertiaryContainer: Color @Composable get() = LocalTilawaPalette.current.tertiaryContainer
    val OnTertiaryContainer: Color @Composable get() = LocalTilawaPalette.current.onTertiaryContainer
    val Error: Color @Composable get() = LocalTilawaPalette.current.error
    val OnError: Color @Composable get() = LocalTilawaPalette.current.onError
    val ErrorContainer: Color @Composable get() = LocalTilawaPalette.current.errorContainer
    val OnErrorContainer: Color @Composable get() = LocalTilawaPalette.current.onErrorContainer
    val PrimaryFixed: Color @Composable get() = LocalTilawaPalette.current.primaryFixed
    val PrimaryFixedDim: Color @Composable get() = LocalTilawaPalette.current.primaryFixedDim
    val OnPrimaryFixed: Color @Composable get() = LocalTilawaPalette.current.onPrimaryFixed
    val OnPrimaryFixedVariant: Color @Composable get() = LocalTilawaPalette.current.onPrimaryFixedVariant
    val SecondaryFixed: Color @Composable get() = LocalTilawaPalette.current.secondaryFixed
    val SecondaryFixedDim: Color @Composable get() = LocalTilawaPalette.current.secondaryFixedDim
    val OnSecondaryFixed: Color @Composable get() = LocalTilawaPalette.current.onSecondaryFixed
    val OnSecondaryFixedVariant: Color @Composable get() = LocalTilawaPalette.current.onSecondaryFixedVariant
    val TertiaryFixed: Color @Composable get() = LocalTilawaPalette.current.tertiaryFixed
    val TertiaryFixedDim: Color @Composable get() = LocalTilawaPalette.current.tertiaryFixedDim
    val OnTertiaryFixed: Color @Composable get() = LocalTilawaPalette.current.onTertiaryFixed
    val OnTertiaryFixedVariant: Color @Composable get() = LocalTilawaPalette.current.onTertiaryFixedVariant
    val Background: Color @Composable get() = LocalTilawaPalette.current.background
    val OnBackground: Color @Composable get() = LocalTilawaPalette.current.onBackground
    val SurfaceVariant: Color @Composable get() = LocalTilawaPalette.current.surfaceVariant
    val FeedbackCorrect: Color @Composable get() = LocalTilawaPalette.current.feedbackCorrect
    val FeedbackDuration: Color @Composable get() = LocalTilawaPalette.current.feedbackDuration
    val MushafRule: Color @Composable get() = LocalTilawaPalette.current.mushafRule
    val MushafAccent: Color @Composable get() = LocalTilawaPalette.current.mushafAccent
}

/** Construit un `darkColorScheme`/`lightColorScheme` Material 3 complet a partir d'une `TilawaPalette` - pour `MaterialTheme(colorScheme=...)`, voir Theme.kt. */
fun TilawaPalette.toDarkColorScheme() = darkColorScheme(
    primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary, secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer, tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer, background = background, onBackground = onBackground, surface = surface,
    onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant, surfaceTint = surfaceTint,
    inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface, error = error, onError = onError,
    errorContainer = errorContainer, onErrorContainer = onErrorContainer, outline = outline, outlineVariant = outlineVariant,
    surfaceContainerLowest = surfaceContainerLowest, surfaceContainerLow = surfaceContainerLow, surfaceContainer = surfaceContainer,
    surfaceContainerHigh = surfaceContainerHigh, surfaceContainerHighest = surfaceContainerHighest, surfaceBright = surfaceBright,
    surfaceDim = surfaceDim,
)

fun TilawaPalette.toLightColorScheme() = lightColorScheme(
    primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary, secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer, tertiary = tertiary, onTertiary = onTertiary, tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer, background = background, onBackground = onBackground, surface = surface,
    onSurface = onSurface, surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant, surfaceTint = surfaceTint,
    inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface, error = error, onError = onError,
    errorContainer = errorContainer, onErrorContainer = onErrorContainer, outline = outline, outlineVariant = outlineVariant,
    surfaceContainerLowest = surfaceContainerLowest, surfaceContainerLow = surfaceContainerLow, surfaceContainer = surfaceContainer,
    surfaceContainerHigh = surfaceContainerHigh, surfaceContainerHighest = surfaceContainerHighest, surfaceBright = surfaceBright,
    surfaceDim = surfaceDim,
)
