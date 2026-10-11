package com.tarteelwarsh.tilawa.ui.sessionsummary

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tarteelwarsh.tilawa.mushaf.RevealCounts
import com.tarteelwarsh.tilawa.ui.theme.TilawaColors
import com.tarteelwarsh.tilawa.ui.theme.TilawaType
import com.tarteelwarsh.tilawa.ui.theme.TilawaWarshTheme

/**
 * Port SIMPLIFIE, sur decision explicite, du mockup
 * `stitch_tarteel_warsh_ai_teacher/session_summary` : "légende avec les
 * compteurs bruts uniquement" - retire par rapport au mockup :
 * - le donut de precision globale + etiquette qualitative ("85% - ممتاز") :
 *   exigerait de definir un seuil de "reussite" qui n'existe nulle part
 *   cote Dani_app/Corrector (colors est un code par caractere, pas un score
 *   pass/fail) ;
 * - la ventilation par regle de tajwid ("غنة ناقصة، مد قصير"...) : `Corrector`
 *   ne categorise pas SES erreurs par regle, seulement par code 1/0/-1/2 ;
 * - la carte de recommandation de lecon : aucune donnee de mapping
 *   lecon<->erreur n'existe dans ce projet.
 * Garde : les 3 compteurs bruts (RevealState.counts(), voir sa KDoc) et la
 * duree de session.
 *
 * Libelle du bouton principal corrige - bug reel trouve sur device : il
 * affichait encore "إنهاء وحفظ الجلسة" (terminer ET enregistrer la session)
 * alors que la sauvegarde (`SessionHistoryStore.append`) a deja eu lieu AVANT
 * meme que cet ecran s'affiche (`LiveCorrectionViewModel.endSession()` termine
 * l'enregistrement puis navigue ici) - le bouton ne fait plus, a ce stade,
 * que fermer l'ecran (meme callback que le X). Le libelle promettait donc une
 * action deja faite - trompeur, corrige en "تم" (Fait), qui ne pretend rien
 * de plus que ce que le bouton fait reellement.
 */
@Composable
fun SessionSummaryScreen(counts: RevealCounts, sessionDurationLabel: String, onCloseClick: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(containerColor = TilawaColors.Background) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                TopBar(onCloseClick)
                Spacer(Modifier.height(24.dp))
                Text("مدة الجلسة : $sessionDurationLabel", style = TilawaType.BodyLg, color = TilawaColors.OnSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))

                CounterRow("تلاوة صحيحة", counts.correct, TilawaColors.FeedbackCorrect)
                Spacer(Modifier.height(12.dp))
                CounterRow("أخطاء تجويدية (تنبيه)", counts.duration, TilawaColors.FeedbackDuration)
                Spacer(Modifier.height(12.dp))
                CounterRow("أخطاء جلية (لحن)", counts.error, TilawaColors.Error)

                Spacer(Modifier.height(32.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(TilawaColors.Primary)
                        .clickable(onClick = onCloseClick)
                        .padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text("تم", style = TilawaType.HeadlineMd, color = TilawaColors.OnPrimary)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TopBar(onCloseClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.width(48.dp))
        Text("ملخص الجلسة", style = TilawaType.HeadlineLg, color = TilawaColors.Primary)
        IconButton(onClick = onCloseClick) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = TilawaColors.OnSurfaceVariant)
        }
    }
}

@Composable
private fun CounterRow(label: String, count: Int, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TilawaColors.SurfaceContainerLow)
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        Text(label, style = TilawaType.BodyLg, color = TilawaColors.OnSurface, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        Text(count.toString(), style = TilawaType.HeadlineLg.copy(fontSize = 22.sp), color = color)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121414)
@Composable
private fun SessionSummaryScreenPreview() {
    TilawaWarshTheme {
        SessionSummaryScreen(counts = RevealCounts(correct = 105, duration = 10, error = 5), sessionDurationLabel = "45 دقيقة", onCloseClick = {})
    }
}
