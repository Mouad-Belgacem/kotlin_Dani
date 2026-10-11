package com.tarteelwarsh.tilawa.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable

/**
 * `appearanceMode` : AUTO (defaut) suit `isSystemInDarkTheme()`, DARK/LIGHT
 * forcent - voir `AppearanceMode` et `TilawaDarkPalette`/`TilawaLightPalette`
 * (Color.kt). Fournit `LocalTilawaPalette` (lu par `TilawaColors`, 122 sites
 * d'appel dans l'app - reactifs au theme sans modification) ET construit le
 * `ColorScheme` Material 3 correspondant pour `MaterialTheme`.
 */
@Composable
fun TilawaWarshTheme(appearanceMode: AppearanceMode = AppearanceMode.DARK, content: @Composable () -> Unit) {
    val isDark = when (appearanceMode) {
        AppearanceMode.AUTO -> isSystemInDarkTheme()
        AppearanceMode.DARK -> true
        AppearanceMode.LIGHT -> false
    }
    val palette = if (isDark) TilawaDarkPalette else TilawaLightPalette
    val colorScheme = if (isDark) palette.toDarkColorScheme() else palette.toLightColorScheme()

    CompositionLocalProvider(LocalTilawaPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
