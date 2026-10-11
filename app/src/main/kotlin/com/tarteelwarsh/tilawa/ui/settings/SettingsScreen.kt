package com.tarteelwarsh.tilawa.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tarteelwarsh.tilawa.ui.theme.AppearanceMode
import com.tarteelwarsh.tilawa.ui.theme.TilawaColors
import com.tarteelwarsh.tilawa.ui.theme.TilawaShapes
import com.tarteelwarsh.tilawa.ui.theme.TilawaType
import com.tarteelwarsh.tilawa.ui.theme.TilawaWarshTheme

/**
 * Port du mockup `stitch_tarteel_warsh_ai_teacher/settings`, avec deux
 * decisions produit deja actees appliquees ici (pas dans le mockup lui-meme,
 * qui les montre "actives") :
 * - Selecteur de riwaya : GRISE sauf Warsh - une seule riwaya est reellement
 *   supportee (le pipeline entier - segmenteur, ASR, Corrector, Finder - est
 *   entraine/construit specifiquement pour Warsh 'an Nafi', voir README
 *   racine) ; presenter les autres options comme choisissables serait
 *   trompeur.
 * - PAS de curseur de sensibilite du microphone - `core-audio.AudioCapture`
 *   n'expose aucun gain/seuil reglable (capture brute a taux fixe), un
 *   curseur ici n'aurait rien a controler.
 *
 * Theme clair/sombre desormais REELLEMENT fonctionnel (`appearanceMode`/
 * `onAppearanceModeChange`, persiste via SharedPreferences - voir
 * `LiveCorrectionViewModel.setAppearanceMode`/Theme.kt). "تلاوة الخلفية"
 * reste pour l'instant visuel uniquement (pas de service de lecture en
 * arriere-plan) - documente explicitement ici et dans app/README.md, pas une
 * omission silencieuse.
 *
 * Curseur "حجم النص" (taille du texte) RETIRE - bug reel signale par
 * l'utilisateur sur device (le curseur ne changeait rien a l'affichage) :
 * c'etait un simple `Slider` local (`mutableFloatStateOf`) qui ne pilotait
 * aucune echelle de police reelle, juste une decoration qui bougeait sans
 * effet. Pas de mise a l'echelle de police disponible nulle part dans ce
 * port (`TilawaType` a des tailles fixes en sp) - implementer une VRAIE
 * echelle de police serait un chantier a part (filtrer chaque `TextStyle`
 * affiche a travers un facteur d'echelle) ; retirer le controle trompeur
 * plutot que le laisser en place a ete la decision explicite de
 * l'utilisateur ("si c'est pas necessaire tu peux l'enlever").
 */
@Composable
fun SettingsScreen(appearanceMode: AppearanceMode, onAppearanceModeChange: (AppearanceMode) -> Unit, onCloseClick: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(containerColor = TilawaColors.Background) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                TopBar(onCloseClick)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Text("الإعدادات", style = TilawaType.HeadlineLg.copy(fontSize = 26.sp), color = TilawaColors.OnBackground)
                    Spacer(Modifier.height(4.dp))
                    Text("تخصيص تجربة التلاوة الخاصة بك", style = TilawaType.BodyLg, color = TilawaColors.OnSurfaceVariant)
                    Spacer(Modifier.height(24.dp))

                    SectionTitle("إعدادات القراءة")
                    SettingsCard {
                        RiwayaSelector()
                    }

                    Spacer(Modifier.height(24.dp))
                    SectionTitle("الصوت والميكروفون")
                    SettingsCard {
                        BackgroundRecitationToggle()
                    }

                    Spacer(Modifier.height(24.dp))
                    SectionTitle("المظهر")
                    SettingsCard {
                        ThemeSelector(appearanceMode, onAppearanceModeChange)
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun TopBar(onCloseClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(TilawaColors.Surface).padding(horizontal = 4.dp).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onCloseClick) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = TilawaColors.Primary)
        }
        Text("الإعدادات", style = TilawaType.HeadlineLg, color = TilawaColors.Primary)
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = TilawaType.HeadlineMd, color = TilawaColors.Primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TilawaColors.SurfaceContainerLow)
            .border(1.dp, TilawaColors.Primary.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
            .padding(20.dp),
        content = content,
    )
}

@Composable
private fun RiwayaSelector() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text("الرواية", style = TilawaType.BodyLg, color = TilawaColors.OnSurface)
    }
    Spacer(Modifier.height(8.dp))
    // Grise volontairement - une seule riwaya reellement disponible (voir KDoc de fichier).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TilawaColors.SurfaceContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("ورش عن نافع", style = TilawaType.BodyLg, color = TilawaColors.OnSurfaceVariant)
        Text("الوحيدة المتاحة", style = TilawaType.LabelSm, color = TilawaColors.OutlineVariant)
    }
}

@Composable
private fun BackgroundRecitationToggle() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("تلاوة الخلفية", style = TilawaType.BodyLg, color = TilawaColors.OnSurface)
            Text("استمرار الصوت عند إغلاق الشاشة", style = TilawaType.LabelSm, color = TilawaColors.OnSurfaceVariant)
        }
        var checked by remember { mutableStateOf(false) }
        Switch(
            checked = checked,
            onCheckedChange = { checked = it },
            colors = SwitchDefaults.colors(checkedThumbColor = TilawaColors.OnPrimary, checkedTrackColor = TilawaColors.Primary),
        )
    }
}

@Composable
private fun ThemeSelector(mode: AppearanceMode, onChange: (AppearanceMode) -> Unit) {
    Text("السمة", style = TilawaType.BodyLg, color = TilawaColors.OnSurface)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        ThemeOption("تلقائي", AppearanceMode.AUTO, mode, onClick = onChange, modifier = Modifier.weight(1f))
        ThemeOption("داكن", AppearanceMode.DARK, mode, onClick = onChange, modifier = Modifier.weight(1f))
        ThemeOption("فاتح", AppearanceMode.LIGHT, mode, onClick = onChange, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ThemeOption(label: String, value: AppearanceMode, selected: AppearanceMode, onClick: (AppearanceMode) -> Unit, modifier: Modifier = Modifier) {
    val isSelected = value == selected
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) TilawaColors.Primary.copy(alpha = 0.12f) else TilawaColors.SurfaceContainer)
            .border(1.dp, if (isSelected) TilawaColors.Primary else TilawaColors.OutlineVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .clickable { onClick(value) }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TilawaType.LabelSm, color = if (isSelected) TilawaColors.Primary else TilawaColors.OnSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121414)
@Composable
private fun SettingsScreenPreview() {
    TilawaWarshTheme { SettingsScreen(appearanceMode = AppearanceMode.DARK, onAppearanceModeChange = {}, onCloseClick = {}) }
}
