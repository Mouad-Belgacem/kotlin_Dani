package com.tarteelwarsh.tilawa.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Rayons repris directement des classes Tailwind REELLEMENT utilisees dans
 * live_correction/code.html, pas de la table `rounded:` du front-matter de
 * DESIGN.md - les deux se contredisent (le YAML de prose donne
 * DEFAULT=0.5rem/lg=1rem/xl=1.5rem, alors que le `tailwind.config` du code
 * genere, ce qui a effectivement ete rendu dans screen.png, donne
 * DEFAULT=0.25rem/lg=0.5rem/xl=0.75rem). Plutot que d'arbitrer entre deux
 * echelles de noms qui se contredisent, chaque rayon ci-dessous reproduit la
 * valeur REELLEMENT rendue pour l'element concerne (sans ambiguite, lisible
 * directement dans les classes `rounded-*` du HTML).
 */
object TilawaShapes {
    /** `rounded-full` (pill de sourate, bouton micro). */
    val Full = CircleShape

    /** `rounded-2xl` (carte de texte de recitation) - echelle Tailwind par defaut (non redefinie dans le config custom) = 1rem. */
    val TextCard = RoundedCornerShape(16.dp)

    /** `rounded-t-xl` (barre de navigation basse) - xl du config custom = 0.75rem, coins hauts uniquement. */
    val BottomNavTop = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
}
