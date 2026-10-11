package com.tarteelwarsh.tilawa.ui.suraselection

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
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
import com.tarteelwarsh.tilawa.mushaf.RecitationMode
import com.tarteelwarsh.tilawa.mushaf.SuraMeta
import com.tarteelwarsh.tilawa.ui.theme.TilawaColors
import com.tarteelwarsh.tilawa.ui.theme.TilawaShapes
import com.tarteelwarsh.tilawa.ui.theme.TilawaType
import com.tarteelwarsh.tilawa.ui.theme.TilawaWarshTheme

/**
 * Port du mockup `stitch_tarteel_warsh_ai_teacher/sura_selection` (recherche,
 * onglets تلاوة/مراجعة, cartes de sourate) - liste elle-meme absente de
 * Dani_app (qui n'a qu'une sidebar minimaliste, `app.js::renderSurahList`,
 * numero+nom seuls) : suit ici le mockup Stitch pour la mise en forme, PAS
 * une reinterprétation libre. `مكية/ayahCount` (SuraMeta) sont une
 * metadonnee decorative absente de Dani_app - voir SuraMeta.kt.
 *
 * `تلاوة` (talawa) / `مراجعة` (muraja3a, defaut) : desormais REELLEMENT
 * cable (`mode`/`onModeChange`, pas un etat local decoratif) - miroir de
 * `recitationMode` (record.js:63-82) + du parametre `mode` envoye a
 * l'ouverture du websocket (api/main.py:36-40) : `offset` 1 vs 2 cote
 * Corrector (largeur de recherche + `add_harakat`) ET attenuation des
 * couleurs d'erreur individuelles cote reveal (`RevealState`). Pris en
 * compte au PROCHAIN demarrage de session (voir
 * `LiveCorrectionViewModel.setRecitationMode`), pas modifiable en cours de
 * route - meme contrainte que Dani_app.
 */
@Composable
fun SuraSelectionScreen(
    suras: List<SuraMeta>,
    mode: RecitationMode,
    onModeChange: (RecitationMode) -> Unit,
    onSuraClick: (Int) -> Unit,
    onBackClick: () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(containerColor = TilawaColors.Background) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                TopBarWithMenu(onMenuClick = onBackClick)

                var query by remember { mutableStateOf("") }

                Column(Modifier.padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(16.dp))
                    SearchField(query, onQueryChange = { query = it })
                    Spacer(Modifier.height(16.dp))
                    ModeTabs(
                        reviewMode = mode == RecitationMode.MURAJAA,
                        onToggle = { isReview -> onModeChange(if (isReview) RecitationMode.MURAJAA else RecitationMode.TALAWA) },
                    )
                    Spacer(Modifier.height(16.dp))
                }

                val filtered = suras.filter { query.isBlank() || it.name.contains(query) || it.number.toString().contains(query) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(filtered, key = { it.number }) { sura ->
                        SuraCard(sura, onClick = { onSuraClick(sura.number) })
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TopBarWithMenu(onMenuClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(TilawaColors.Surface).padding(horizontal = 4.dp).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onMenuClick) {
            Icon(Icons.Filled.Menu, contentDescription = null, tint = TilawaColors.Primary)
        }
        Text("السور", style = TilawaType.HeadlineLg, color = TilawaColors.Primary)
        IconButton(onClick = {}) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = TilawaColors.Primary)
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TilawaShapes.Full)
            .background(TilawaColors.SurfaceContainer)
            .border(1.dp, TilawaColors.Primary.copy(alpha = 0.15f), TilawaShapes.Full)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("بحث عن سورة...", style = TilawaType.BodyLg, color = TilawaColors.OnSurfaceVariant)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = TilawaType.BodyLg.copy(color = TilawaColors.OnSurface, textAlign = TextAlign.Right),
                singleLine = true,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(TilawaColors.Primary),
            )
        }
        Icon(Icons.Filled.Search, contentDescription = null, tint = TilawaColors.OnSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ModeTabs(reviewMode: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TilawaShapes.Full)
            .background(TilawaColors.SurfaceContainer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ModeTab("وضع التلاوة", selected = !reviewMode, onClick = { onToggle(false) }, modifier = Modifier.weight(1f))
        ModeTab("وضع المراجعة", selected = reviewMode, onClick = { onToggle(true) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(TilawaShapes.Full)
            .then(if (selected) Modifier.background(TilawaColors.SurfaceContainerHigh).border(1.dp, TilawaColors.Primary.copy(alpha = 0.3f), TilawaShapes.Full) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TilawaType.LabelSm, color = if (selected) TilawaColors.Primary else TilawaColors.OnSurfaceVariant)
    }
}

@Composable
private fun SuraCard(sura: SuraMeta, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TilawaColors.SurfaceContainerLow)
            .border(1.dp, TilawaColors.Primary.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).border(1.dp, TilawaColors.Primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(sura.number.toString(), style = TilawaType.LabelSm, color = TilawaColors.Primary)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(sura.name, style = TilawaType.HeadlineMd.copy(fontSize = 20.sp), color = TilawaColors.OnSurface)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${sura.ayahCount} آية", style = TilawaType.LabelSm, color = TilawaColors.OnSurfaceVariant)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(TilawaColors.SurfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(if (sura.meccan) "مكية" else "مدنية", style = TilawaType.LabelSm, color = TilawaColors.OnSurfaceVariant)
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121414)
@Composable
private fun SuraSelectionScreenPreview() {
    TilawaWarshTheme {
        SuraSelectionScreen(
            suras = listOf(
                SuraMeta(1, "الفَاتِحة", 7, true),
                SuraMeta(2, "البَقَرَة", 286, false),
                SuraMeta(3, "آل عِمران", 200, false),
                SuraMeta(4, "النِّسَاء", 176, false),
            ),
            mode = RecitationMode.MURAJAA,
            onModeChange = {},
            onSuraClick = {},
            onBackClick = {},
        )
    }
}
