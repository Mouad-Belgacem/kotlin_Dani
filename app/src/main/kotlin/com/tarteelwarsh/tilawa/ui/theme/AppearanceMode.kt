package com.tarteelwarsh.tilawa.ui.theme

/**
 * Miroir de `theme` (Dani_app/web/index.html + app.js::setTheme) : choix
 * explicite persiste (ici, SharedPreferences - la, `localStorage`) qui
 * gagne sur tout, sinon repli automatique. Dani_app replie sur
 * `matchMedia("(max-width: 700px)")` (mobile = sombre par defaut, desktop =
 * clair) - sans objet ici (toujours mobile) : AUTO replie sur
 * `isSystemInDarkTheme()` a la place, l'equivalent mobile naturel de
 * "preference systeme".
 */
enum class AppearanceMode { AUTO, DARK, LIGHT }
