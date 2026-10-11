package com.tarteelwarsh.tilawa

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tarteelwarsh.tilawa.ui.history.SessionHistoryScreen
import com.tarteelwarsh.tilawa.ui.livecorrection.LiveCorrectionViewModel
import com.tarteelwarsh.tilawa.ui.mushaf.MushafScreen
import com.tarteelwarsh.tilawa.ui.sessionsummary.SessionSummaryScreen
import com.tarteelwarsh.tilawa.ui.settings.SettingsScreen
import com.tarteelwarsh.tilawa.ui.suraselection.SuraSelectionScreen
import com.tarteelwarsh.tilawa.ui.theme.TilawaWarshTheme

/** Navigation manuelle a 5 ecrans - pas de Navigation-Compose (nouvelle dependance evitee pour ce perimetre). */
private sealed interface Screen {
    data object Mushaf : Screen
    data object SuraSelection : Screen
    data object Settings : Screen
    data object SessionSummary : Screen
    data object SessionHistory : Screen
}

class MainActivity : ComponentActivity() {

    private val viewModel: LiveCorrectionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Charge le pipeline (modeles + page 1) DES le lancement, sans
        // attendre un premier tap - miroir du vrai Dani_app (app.js, fin de
        // fichier : `renderPage(1)` appele INCONDITIONNELLEMENT au chargement,
        // pas derriere un bouton). Corrige un ecart reel de ce port : l'app
        // demarrait jusqu'ici sur un ecran totalement vide.
        viewModel.ensurePipelineLoaded(applicationContext)

        val requestMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.startListening(applicationContext)
        }

        setContent {
            val appearanceMode by viewModel.appearanceMode.collectAsState()
            TilawaWarshTheme(appearanceMode = appearanceMode) {
                var screen by remember { mutableStateOf<Screen>(Screen.Mushaf) }

                when (val current = screen) {
                    is Screen.Mushaf -> {
                        val state by viewModel.uiState.collectAsState()
                        MushafScreen(
                            state = state,
                            onMicClick = {
                                when {
                                    // Deja en cours de finalisation (endSession lance) - ignore
                                    // le tap plutot que de relancer une 2e finalisation en
                                    // parallele (confus, double-clic pendant le drain/flush).
                                    state.isFinalizing -> {}
                                    state.isListening -> {
                                        // Controle d'arret EXPLICITE et fonctionnel (pas un
                                        // simple stopListening abrupt) : vide le backlog deja
                                        // capture, flush() l'encodeur streaming, finalise la
                                        // derniere correction, persiste un SessionRecord, PUIS
                                        // navigue vers le resume - voir
                                        // LiveCorrectionViewModel.endSession().
                                        viewModel.endSession(applicationContext) { screen = Screen.SessionSummary }
                                    }
                                    viewModel.hasRecordAudioPermission(this) -> viewModel.startListening(applicationContext)
                                    else -> requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onPeekToggle = viewModel::togglePeekMode,
                            onTuneClick = { screen = Screen.Settings },
                            onHistoryClick = {
                                viewModel.loadSessionHistory(applicationContext)
                                screen = Screen.SessionHistory
                            },
                            onBrowseClick = {
                                viewModel.ensurePipelineLoaded(applicationContext)
                                screen = Screen.SuraSelection
                            },
                            onPreviousPage = { viewModel.goToPage(applicationContext, -1) },
                            onNextPage = { viewModel.goToPage(applicationContext, +1) },
                            onFrameDisplayed = viewModel::onFrameDisplayed,
                        )
                    }
                    is Screen.SuraSelection -> {
                        val suras by viewModel.suraList.collectAsState()
                        val mode by viewModel.recitationMode.collectAsState()
                        SuraSelectionScreen(
                            suras = suras,
                            mode = mode,
                            onModeChange = viewModel::setRecitationMode,
                            onSuraClick = { sura ->
                                viewModel.jumpToSura(applicationContext, sura)
                                screen = Screen.Mushaf
                            },
                            onBackClick = { screen = Screen.Mushaf },
                        )
                    }
                    is Screen.Settings -> {
                        SettingsScreen(
                            appearanceMode = appearanceMode,
                            onAppearanceModeChange = viewModel::setAppearanceMode,
                            onCloseClick = { screen = Screen.Mushaf },
                        )
                    }
                    is Screen.SessionSummary -> {
                        val (counts, duration) = remember(current) { viewModel.sessionSummary() }
                        SessionSummaryScreen(counts = counts, sessionDurationLabel = duration, onCloseClick = { screen = Screen.Mushaf })
                    }
                    is Screen.SessionHistory -> {
                        val records by viewModel.sessionHistory.collectAsState()
                        val suras by viewModel.suraList.collectAsState()
                        SessionHistoryScreen(
                            records = records,
                            suraNames = suras.map { it.name },
                            onBackClick = { screen = Screen.Mushaf },
                        )
                    }
                }
            }
        }
    }
}
