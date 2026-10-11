package com.tarteelwarsh.tilawa.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tarteelwarsh.tilawa.history.SessionRecord
import com.tarteelwarsh.tilawa.ui.theme.TilawaColors
import com.tarteelwarsh.tilawa.ui.theme.TilawaType
import com.tarteelwarsh.tilawa.ui.theme.TilawaWarshTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Historique des sessions terminees - PAS un ecran Dani_app (aucune
 * persistance n'existe cote Dani_app). Reprend l'icone "history" de la
 * barre du bas de `MushafScreen`, qui naviguait jusqu'ici vers
 * `sura_selection` (nom trompeur - "historique" qui montrait une liste de
 * sourates) : desormais elle montre un VRAI historique, maintenant que
 * `SessionHistoryStore` persiste reellement quelque chose (voir
 * `LiveCorrectionViewModel.endSession`).
 */
@Composable
fun SessionHistoryScreen(records: List<SessionRecord>, suraNames: List<String>, onBackClick: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(containerColor = TilawaColors.Background) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                TopBar(onBackClick)
                if (records.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "لا توجد جلسات محفوظة بعد - أنهِ أول جلسة تلاوة لتظهر هنا",
                            style = TilawaType.BodyLg,
                            color = TilawaColors.OnSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { Spacer(Modifier.height(4.dp)) }
                        items(records, key = { it.timestampMillis }) { record ->
                            SessionRecordCard(record, suraNames)
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(onBackClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(TilawaColors.Surface).padding(horizontal = 4.dp).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onBackClick) {
            Icon(Icons.Filled.Menu, contentDescription = null, tint = TilawaColors.Primary)
        }
        Text("سجل الجلسات", style = TilawaType.HeadlineLg, color = TilawaColors.Primary)
        Spacer(Modifier.width(48.dp))
    }
}

private fun suraLabel(number: Int, names: List<String>): String = names.getOrNull(number - 1)?.let { "سورة $it" } ?: "سورة $number"

@Composable
private fun SessionRecordCard(record: SessionRecord, suraNames: List<String>) {
    val dateLabel = remember(record.timestampMillis) {
        SimpleDateFormat("d MMM yyyy - HH:mm", Locale.US).format(Date(record.timestampMillis))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TilawaColors.SurfaceContainerLow)
            .border(1.dp, TilawaColors.Primary.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(dateLabel, style = TilawaType.LabelSm, color = TilawaColors.OnSurfaceVariant)
            Text("${record.durationMinutes} دقيقة", style = TilawaType.LabelSm, color = TilawaColors.OnSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${suraLabel(record.startSura, suraNames)} → ${suraLabel(record.endSura, suraNames)}",
            style = TilawaType.HeadlineMd,
            color = TilawaColors.OnSurface,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CounterChip(record.correct, TilawaColors.FeedbackCorrect)
            CounterChip(record.duration, TilawaColors.FeedbackDuration)
            CounterChip(record.error, TilawaColors.Error)
            Text("ثقة ${String.format(Locale.US, "%.1f", record.trust)}/6", style = TilawaType.LabelSm, color = TilawaColors.Primary)
        }
    }
}

@Composable
private fun CounterChip(count: Int, color: androidx.compose.ui.graphics.Color) {
    Text(count.toString(), style = TilawaType.LabelSm, color = color)
}

@Preview(showBackground = true, backgroundColor = 0xFF121414)
@Composable
private fun SessionHistoryScreenPreview() {
    TilawaWarshTheme {
        SessionHistoryScreen(
            records = listOf(
                SessionRecord(System.currentTimeMillis(), 1, 1, 12, 25, 56, 4, 1, 4.2, 12),
            ),
            suraNames = listOf("الفَاتِحة"),
            onBackClick = {},
        )
    }
}
