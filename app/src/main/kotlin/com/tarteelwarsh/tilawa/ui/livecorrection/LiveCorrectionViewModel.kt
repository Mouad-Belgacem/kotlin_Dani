package com.tarteelwarsh.tilawa.ui.livecorrection

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tarteelwarsh.tilawa.asr.StreamingMeta
import com.tarteelwarsh.tilawa.asr.StreamingZipformerEncoder
import com.tarteelwarsh.tilawa.audio.AudioCapture
import com.tarteelwarsh.tilawa.history.SessionHistoryStore
import com.tarteelwarsh.tilawa.history.SessionRecord
import com.tarteelwarsh.tilawa.logic.correction.Finder
import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonArray
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonString
import com.tarteelwarsh.tilawa.logic.tokenizer.RunAwareTokenizer
import com.tarteelwarsh.tilawa.mushaf.BasmalaIndex
import com.tarteelwarsh.tilawa.mushaf.JuzIndex
import com.tarteelwarsh.tilawa.mushaf.MushafPaint
import com.tarteelwarsh.tilawa.mushaf.PageIndex
import com.tarteelwarsh.tilawa.mushaf.RecitationMode
import com.tarteelwarsh.tilawa.mushaf.RevealCounts
import com.tarteelwarsh.tilawa.mushaf.RevealState
import com.tarteelwarsh.tilawa.mushaf.SuraMeta
import com.tarteelwarsh.tilawa.mushaf.SuraMetaLoader
import com.tarteelwarsh.tilawa.pipeline.ChunkStageTiming
import com.tarteelwarsh.tilawa.pipeline.ExperimentalCtcOnlyPipelineSession
import com.tarteelwarsh.tilawa.pipeline.PipelineResult
import com.tarteelwarsh.tilawa.ui.mushaf.MushafPageContent
import com.tarteelwarsh.tilawa.ui.mushaf.MushafPageItem
import com.tarteelwarsh.tilawa.ui.mushaf.MushafUiState
import com.tarteelwarsh.tilawa.ui.theme.AppearanceMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Charge les modeles + demarre/arrete la capture reelle -
 * `core-audio.AudioCapture` -> `core-pipeline.PipelineSession.processChunk()`
 * -> `LiveCorrectionUiState`. Instrumente la latence son -> affichage : voir
 * `capturedAtNanos`/`resultId` dans l'etat et `LaunchedEffect` cote
 * `LiveCorrectionScreen` (seul endroit qui sait quand la frame est
 * effectivement dessinee).
 */
class LiveCorrectionViewModel(application: Application) : AndroidViewModel(application) {

    // Ecran principal desormais MushafScreen (page/reveal reel, voir
    // app/README.md) - LiveCorrectionScreen/LiveCorrectionUiState sont
    // superseded (mockup clair/carte compacte qui ne correspond pas au vrai
    // Dani_app, voir la discussion qui a motive ce changement).
    private val _uiState = MutableStateFlow(MushafUiState())
    val uiState: StateFlow<MushafUiState> = _uiState.asStateFlow()

    private val _suraList = MutableStateFlow<List<SuraMeta>>(emptyList())
    val suraList: StateFlow<List<SuraMeta>> = _suraList.asStateFlow()

    // "وضع التلاوة"/"وضع المراجعة" - miroir de recitationMode (Dani_app/web/record.js:63-82)
    // + du parametre `mode` envoye UNE SEULE FOIS a l'ouverture du websocket
    // (api/main.py:36) : choisi avant de demarrer, PAS modifiable pendant
    // une session (Dani_app ne le renvoie jamais apres coup). Defaut
    // MURAJAA, comme le frontend reel (`localStorage.getItem("recitationMode")
    // === "talawa" ? "talawa" : "muraja3a"` - muraja3a est le repli).
    private val _recitationMode = MutableStateFlow(RecitationMode.MURAJAA)
    val recitationMode: StateFlow<RecitationMode> = _recitationMode.asStateFlow()

    private var models: PipelineModels? = null
    private var captureJob: Job? = null
    private val audioCapture = AudioCapture()
    private var nextResultId = 0L
    private var sessionStartNanos: Long? = null

    // Position de depart + dernier resultat de la session EN COURS - pour
    // persister un SessionRecord a la fin (voir endSession()). PAS un
    // concept Dani_app (aucune persistance de session n'existe cote
    // Dani_app) - ajoute sur demande explicite plutot que de se contenter
    // de corriger le libelle trompeur du bouton de fin de session.
    private var sessionStart: Pair<Int, Int>? = null
    private var lastResultSuraAya: Pair<Int, Int>? = null

    // "السمة" (Settings) - persiste via SharedPreferences (pas de nouvelle
    // dependance, l'equivalent Android direct de `localStorage.setItem("theme", ...)`
    // cote Dani_app, app.js::setTheme). Charge synchroniquement a la
    // creation du ViewModel (lecture SharedPreferences, quasi instantanee -
    // pas besoin d'un etat de chargement separe).
    private val prefs = application.getSharedPreferences("tilawa_prefs", Context.MODE_PRIVATE)
    private val _appearanceMode = MutableStateFlow(
        prefs.getString(PREF_APPEARANCE_MODE, null)?.let { runCatching { AppearanceMode.valueOf(it) }.getOrNull() } ?: AppearanceMode.DARK,  // black-on-white is hard on the eye for long recitation
    )
    val appearanceMode: StateFlow<AppearanceMode> = _appearanceMode.asStateFlow()

    fun setAppearanceMode(mode: AppearanceMode) {
        _appearanceMode.value = mode
        prefs.edit().putString(PREF_APPEARANCE_MODE, mode.name).apply()
    }

    private val _sessionHistory = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessionHistory: StateFlow<List<SessionRecord>> = _sessionHistory.asStateFlow()

    fun loadSessionHistory(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            // load() is already non-throwing, but an uncaught exception in a
            // viewModelScope child reaches the default handler and crashes the app -
            // on every launch, if the cause is a file on disk. Belt and braces.
            _sessionHistory.value = try {
                SessionHistoryStore.load(context.applicationContext).sortedByDescending { it.timestampMillis }
            } catch (e: Exception) {
                Log.e(TAG, "lecture de l'historique impossible", e)
                emptyList()
            }
        }
    }

    fun hasRecordAudioPermission(context: Context): Boolean = com.tarteelwarsh.tilawa.audio.hasRecordAudioPermission(context)

    /**
     * Choix du mode - Dani_app l'envoie une seule fois a l'ouverture du
     * websocket (record.js:711) et ne le change plus jamais pendant la
     * session ; ce port reproduit la meme contrainte : sans effet une fois
     * une session demarree (`isListening`), pris en compte au PROCHAIN
     * `startListening()`.
     */
    fun setRecitationMode(mode: RecitationMode) {
        if (_uiState.value.isListening) return
        _recitationMode.value = mode
    }

    /**
     * Charge le pipeline (modeles + index mushaf) SANS demarrer la capture -
     * pour l'ecran "sura_selection" (liste des sourates, navigation de page)
     * qui a besoin de `Finder`/`PageIndex`/`SuraMeta` mais pas forcement du
     * micro. Partage le meme cache `models` que `startListening` - un
     * appel ulterieur a l'un ou l'autre ne recharge rien.
     */
    fun ensurePipelineLoaded(context: Context) {
        if (models != null || _uiState.value.isLoadingModels) return
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isLoadingModels = true) }
            val loaded = loadPipelineModels(context.applicationContext)
            val (sura, aya) = loaded.firstAyahOfCurrentPage()
            loaded.startNewSession(_recitationMode.value, sura, aya)
            models = loaded
            _suraList.value = loaded.suraMetaList
            _uiState.update { it.copy(isLoadingModels = false, page = it.page ?: loaded.currentPage(it.peekMode)) }
        }
    }

    /** Ecran "sura_selection" - saute directement au debut de cette sourate (browse, independant de la recitation live). */
    fun jumpToSura(context: Context, sura: Int) {
        val loaded = models
        if (loaded == null) {
            ensurePipelineLoaded(context)
            return
        }
        _uiState.update { it.copy(page = loaded.jumpToSuraForBrowsing(sura, it.peekMode)) }
    }

    /**
     * Boutons de navigation de page (`#prev-page`/`#next-page`, app.js) -
     * absents jusqu'ici de ce port (seul moyen de changer de page : reciter,
     * ou sauter vers une AUTRE sourate). Pas d'effet sur `RevealState` (pure
     * lecture/browse, comme `jumpToSura`) - `delta` = -1 (page precedente,
     * `‹`) ou +1 (page suivante, `›`), clampe silencieusement aux bornes
     * (miroir de `goToPage()`, app.js:248-251).
     */
    fun goToPage(context: Context, delta: Int) {
        val loaded = models
        if (loaded == null) {
            ensurePipelineLoaded(context)
            return
        }
        val page = loaded.goToPage(delta, _uiState.value.peekMode) ?: return
        _uiState.update { it.withPage(page, loaded) }
    }

    /** Compteurs bruts + duree pour l'ecran "session_summary" (voir SessionSummaryScreen KDoc). */
    fun sessionSummary(): Pair<RevealCounts, String> {
        val counts = models?.revealCounts() ?: RevealCounts(0, 0, 0)
        val startedAt = sessionStartNanos
        val minutes = if (startedAt != null) ((System.nanoTime() - startedAt) / 60_000_000_000.0).let { kotlin.math.max(0, it.toInt()) } else 0
        return counts to "$minutes دقيقة"
    }

    fun startListening(context: Context) {
        // Drop a finished/cancelled job before the guard, so a stale reference can
        // never block a restart.
        captureJob?.let { if (!it.isActive) captureJob = null }
        if (captureJob?.isActive == true) {
            Log.w(TAG, "startListening ignore : capture deja active")
            return
        }
        _uiState.update { it.copy(startError = null) }
        sessionStartNanos = System.nanoTime() // toujours reinitialise : chaque startListening() demarre une session FRAICHE (voir startNewSession ci-dessous), jamais une reprise.
        captureJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                _uiState.update { it.copy(isListening = true, isLoadingModels = models == null) }
                val loadStartNanos = System.nanoTime()
                val loaded = models ?: loadPipelineModels(context.applicationContext).also {
                    models = it
                    _suraList.value = it.suraMetaList
                }
                if (models === loaded && System.nanoTime() - loadStartNanos > 100_000_000) {
                    Log.i(TAG, "modeles charges en ${(System.nanoTime() - loadStartNanos) / 1_000_000}ms")
                }
                // Chaque appui sur le micro demarre une session FRAICHE - miroir
                // de Dani_app (une connexion websocket = une session, le mode
                // est envoye UNE FOIS a l'ouverture, jamais change ensuite -
                // record.js:711/api/main.py:36) : reconstruit le Corrector/la
                // session pipeline avec le mode courant et `firstAyahOfCurrentPage()`
                // (miroir exact de `start = firstAyahOfCurrentPage()`, record.js:630 -
                // la position de depart suit la page AFFICHEE au moment d'appuyer
                // sur le micro, pas un point separement memorise), ET reinitialise
                // RevealState (record.js::startRecording - "each take is a fresh
                // attempt, not a continuation of a previous take's judgments", PAS
                // applique avant cette correction : le reveal s'accumulait
                // indefiniment d'une session a l'autre).
                val (startSura, startAya) = loaded.firstAyahOfCurrentPage()
                loaded.startNewSession(_recitationMode.value, startSura, startAya)
                sessionStart = startSura to startAya
                lastResultSuraAya = null
                nextResultId = 0
                _uiState.update {
                    it.copy(
                        isLoadingModels = false,
                        page = loaded.currentPage(it.peekMode),
                        resultId = 0,
                        capturedAtNanos = null,
                        computeLatencyMs = null,
                        latencyMs = null,
                        backlogChunks = 0,
                        trust = null,
                    )
                }

                // Decouplage capture/traitement : la capture ne doit JAMAIS
                // s'arreter, quel que soit le temps que prend le traitement.
                // Canal NON BORNE (Channel.UNLIMITED) plutot qu'un drop des
                // chunks en trop - core-audio garantit un flux gapless et
                // strictement ordonne, et core-asr EN DEPEND (etat Fbank/CTC
                // streaming) : sauter un chunk corromprait silencieusement
                // la transcription (frames non contigues que le modele n'a
                // jamais vues a l'entrainement), ce n'est donc pas une
                // option ici. Le producteur (collecte micro) et le
                // consommateur (traitement pipeline) tournent en parallele,
                // relies uniquement par ce canal - `trySend` sur un canal
                // UNLIMITED ne suspend jamais, donc `AudioRecord.read()`
                // n'est plus jamais bloque par le temps de calcul.
                val channel = Channel<TimestampedChunk>(Channel.UNLIMITED)
                val backlog = AtomicInteger(0)
                var lastUtteranceEndedCount = 0

                val producer = launch {
                    try {
                        audioCapture.start(context.applicationContext).collect { chunk ->
                            backlog.incrementAndGet()
                            channel.trySend(TimestampedChunk(chunk, System.nanoTime()))
                        }
                    } finally {
                        channel.close()
                    }
                }

                for (timestamped in channel) {
                    backlog.decrementAndGet()

                    // Chronometrage REEL par etage (pas une attribution par
                    // raisonnement) - rempli inconditionnellement : encodeur
                    // et segmenteur tournent sur CHAQUE chunk, meme ceux qui
                    // ne produisent pas de resultat.
                    val timing = ChunkStageTiming()
                    // Shed the speculative forward while chunks are queued. It is
                    // an extra full inference on EVERY chunk, so on a device where
                    // one forward costs more than the 100ms chunk period it is
                    // exactly what stops the queue from ever draining. Skipping it
                    // trades provisional detail for catching up, and costs no
                    // correctness: nothing it computes is retained.
                    val result = loaded.processChunk(
                        timestamped.samples, timing, runSpeculative = backlog.get() == 0,
                    )
                    val computedAtNanos = System.nanoTime()
                    val computeLatencyMs = (computedAtNanos - timestamped.capturedAtNanos) / 1_000_000.0
                    val backlogNow = backlog.get()

                    val ctcDebug = loaded.debugCtcState()?.let { " $it" } ?: ""
                    Log.i(
                        TAG,
                        "chunk : fbank=${timing.totalFbankNanos.toMs()}ms " +
                            "(asr=${timing.asrFbankNanos.toMs()}ms seg=${timing.segmenterFbankNanos.toMs()}ms) " +
                            "asrModel=${timing.asrModelNanos.toMs()}ms segModel=${timing.segmenterModelNanos.toMs()}ms " +
                            "corrector=${timing.correctorNanos.toMs()}ms total=${timing.totalNanos.toMs()}ms " +
                            "compute=${computeLatencyMs.toFixed1()}ms backlog=$backlogNow$ctcDebug",
                    )

                    if (result != null) {
                        val id = nextResultId++
                        Log.i(TAG, "resultat #$id : calcul=${computeLatencyMs}ms sura=${result.sura} aya=${result.aya} text=\"${result.text}\"")
                        val peekMode = _uiState.value.peekMode
                        val page = loaded.applyResultAndBuildPage(result, peekMode)
                        lastResultSuraAya = result.sura to result.aya
                        _uiState.update {
                            it.withPage(page, loaded).copy(
                                isListening = true,
                                resultId = id,
                                capturedAtNanos = timestamped.capturedAtNanos,
                                computeLatencyMs = computeLatencyMs,
                                backlogChunks = backlogNow,
                                trust = result.trust,
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(backlogChunks = backlogNow, debugLine = models?.debugLine())
                        }
                    }

                    // AFTER applying the result, never before. The stop is read on
                    // every chunk (right after a waqf there is usually no result at
                    // all, since the text left is shorter than MIN_TILAWA_CHARS, and
                    // that is exactly when the shadow must clear). But running it
                    // FIRST was the bug: on the very chunk the waqf appears, the
                    // shadow was cleared and then applyResult() - still carrying
                    // wasl=true, because a speculative waqf does not cut - wrote
                    // `provisional` straight back over it. The latch meant no second
                    // edge, so the shadow then sat there until the NEXT utterance.
                    val stops = loaded.utteranceEndedCount()
                    if (stops != lastUtteranceEndedCount) {
                        lastUtteranceEndedCount = stops
                        loaded.finalizeShadowAndBuildPage(_uiState.value.peekMode)?.let { page ->
                            _uiState.update { it.copy(page = page) }
                        }
                    }
                }
                producer.join()
            } catch (e: CancellationException) {
                throw e // normal stop - never report it as a failure
            } catch (e: Exception) {
                Log.e(TAG, "startListening a echoue", e)
                // Surface it instead of leaving a dead button: without this the
                // mic looked pressed but nothing was captured, and the only clue
                // was in logcat.
                withContext(NonCancellable + Dispatchers.Main) {
                    _uiState.update { it.copy(startError = e.message ?: e::class.simpleName) }
                }
            } finally {
                // NonCancellable is the fix, not decoration. This block runs on a
                // coroutine that has usually just been CANCELLED by the stop button,
                // and withContext on a cancelled coroutine throws
                // CancellationException immediately - so the update below never ran
                // and `isListening` stayed true forever. The next tap therefore took
                // the stop branch ("session ended") while
                // `if (captureJob?.isActive == true) return` blocked any fresh start:
                // exactly the "sometimes the record button does nothing" symptom.
                withContext(NonCancellable + Dispatchers.Main) {
                    _uiState.update { it.copy(isListening = false) }
                }
            }
        }
    }

    // Locale.US explicite : separateur decimal '.', pas ',' - ces logs sont
    // parses par regex pour l'analyse de latence, pas juste affiches.
    private fun Long.toMs(): String = (this / 1_000_000.0).toFixed1()
    private fun Double.toFixed1(): String = String.format(java.util.Locale.US, "%.1f", this)

    private data class TimestampedChunk(val samples: FloatArray, val capturedAtNanos: Long)

    /** Arret ABRUPT (pas de drain, pas de flush) - reserve a `onCleared()` (l'app se detruit, plus la peine de finaliser proprement). Le bouton micro de l'UI utilise `endSession()`, pas ceci. */
    fun stopListening() {
        audioCapture.stop()
        captureJob?.cancel()
        captureJob = null
    }

    /**
     * Controle d'arret de session EXPLICITE (bouton micro pendant l'ecoute) -
     * contrairement a `stopListening()` (abrupt), celui-ci :
     * 1. arrete la capture (plus de nouveaux chunks) mais laisse le
     *    `captureJob` se terminer NATURELLEMENT (`join()`, pas `cancel()`) -
     *    le canal `Channel.UNLIMITED` (decouplage capture/traitement, voir
     *    startListening) doit finir de vider tout l'audio DEJA capture avant
     *    qu'on considere la session close, sinon les derniers mots recites
     *    juste avant l'arret seraient perdus.
     * 2. appelle `finalizeSession()` (flush de l'encodeur streaming + derniere
     *    correction, voir `PipelineSession.finalizeSession()`) pour recuperer
     *    les derniers symboles encore en attente dans l'encodeur.
     * 3. remet `isListening`/`isFinalizing` a jour et appelle `onFinished`
     *    (navigation vers le resume de session) UNE FOIS tout cela termine -
     *    pas avant, pour que le resume affiche des compteurs a jour.
     */
    fun endSession(context: Context, onFinished: () -> Unit) {
        val jobToAwait = captureJob
        if (jobToAwait == null) {
            onFinished()
            return
        }
        _uiState.update { it.copy(isFinalizing = true) }
        audioCapture.stop()
        viewModelScope.launch(Dispatchers.Default) {
            jobToAwait.join()
            captureJob = null

            val loaded = models
            var finalTrust = _uiState.value.trust ?: 0.0
            if (loaded != null) {
                val timing = ChunkStageTiming()
                val finalResult = loaded.finalizeSession(timing)
                if (finalResult != null) {
                    val id = nextResultId++
                    Log.i(TAG, "resultat #$id (finalise) : sura=${finalResult.sura} aya=${finalResult.aya} text=\"${finalResult.text}\"")
                    val page = loaded.applyResultAndBuildPage(finalResult, _uiState.value.peekMode)
                    lastResultSuraAya = finalResult.sura to finalResult.aya
                    finalTrust = finalResult.trust
                    _uiState.update { it.copy(page = page, resultId = id, trust = finalResult.trust) }
                }
            }

            // Persistance minimale (date, plage recitee, compteurs, confiance) -
            // demandee explicitement plutot que de se contenter de corriger le
            // libelle trompeur du bouton "إنهاء وحفظ الجلسة", qui ne sauvegardait
            // rien jusqu'ici. PAS un concept Dani_app - voir SessionHistoryStore.
            val startedAt = sessionStartNanos
            if (loaded != null && startedAt != null) {
                val (startSura, startAya) = sessionStart ?: (1 to 1)
                val (endSura, endAya) = lastResultSuraAya ?: (startSura to startAya)
                val counts = loaded.revealCounts()
                val minutes = kotlin.math.max(0, ((System.nanoTime() - startedAt) / 60_000_000_000.0).toInt())
                withContext(Dispatchers.IO) {
                    SessionHistoryStore.append(
                        context.applicationContext,
                        SessionRecord(
                            timestampMillis = System.currentTimeMillis(),
                            startSura = startSura,
                            startAya = startAya,
                            endSura = endSura,
                            endAya = endAya,
                            correct = counts.correct,
                            duration = counts.duration,
                            error = counts.error,
                            trust = finalTrust,
                            durationMinutes = minutes,
                        ),
                    )
                }
            }
            sessionStartNanos = null

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isListening = false, isFinalizing = false) }
                onFinished()
            }
        }
    }

    /** Appele par MushafScreen une fois la frame de CE resultat effectivement planifiee (withFrameNanos) - seul endroit qui connait ce timing. */
    fun onFrameDisplayed(resultId: Long, displayLatencyMs: Double) {
        Log.i(TAG, "resultat #$resultId : son->affichage=${displayLatencyMs}ms")
        _uiState.update { if (it.resultId == resultId) it.copy(latencyMs = displayLatencyMs) else it }
    }

    /**
     * Bouton "peek" (revealed_mode/code.html, oeil barre/oeil) - miroir de
     * `peekToggleEl`/`peekMode` (Dani_app/web/record.js) : montre le texte
     * pas encore juge sans le marquer comme recite (ne modifie aucun etat
     * de correction, juste la visibilite). Repeint immediatement la page
     * courante avec le nouveau mode - pas besoin d'attendre un resultat.
     */
    fun togglePeekMode() {
        val loaded = models ?: return
        _uiState.update {
            val newPeek = !it.peekMode
            it.copy(peekMode = newPeek, page = loaded.currentPage(newPeek) ?: it.page)
        }
    }

    override fun onCleared() {
        stopListening()
        models?.close()
    }

    private companion object {
        const val TAG = "LiveCorrectionVM"
        const val PREF_APPEARANCE_MODE = "appearance_mode"

    }

    private suspend fun loadPipelineModels(context: Context): PipelineModels = withContext(Dispatchers.IO) {
        val suraNames = context.assets.open("sura_names.json").use { stream ->
            val text = stream.bufferedReader(Charsets.UTF_8).readText()
            JsonParser.parse(text).asJsonArray().map { it.asJsonString() }
        }

        val zipformerMeta = context.assets.open("model/streaming_meta.json").use { StreamingMeta.parse(it) }
        // commit 32 frames (320ms), left context 512 (~10.2s), built from
        // best2.pt. Geometry is read from streaming_meta.json, so only this
        // filename pins the artefact.
        //
        // 93 classes - the run-aware vocabulary. The syllabic 306-class
        // experiment (best3) was built and reverted: it reached CER 0.00541 at
        // epoch 4 against best2's 0.00459 and was still behind. Its artefacts
        // are kept in warsh_apk/models/ as zipformer_waqf_c32_lc512_b3e4_int8
        // should it ever be revisited, but note that model needs the 306-entry
        // vocab_run_aware.json shipped WITH it - the vocabularies are not
        // interchangeable, and the mismatch is silent rather than fatal.
        val zipformerModelFile = materialize(context, "model/zipformer_waqf_c32_lc512_b2_int8.onnx", "zipformer.onnx")

        val env = OrtEnvironment.getEnvironment()
        // NOT the default SessionOptions. Stock defaults left the encoder running
        // single-threaded with no graph optimisation, and on a real device that
        // measured 321ms of compute per 100ms of audio - the queue could never
        // drain, so the display fell further behind the reciter the longer the
        // session ran ("retard 1 chunk" in the on-screen metrics).
        //
        // Threads are capped at 4 and leave one core free: the audio capture
        // coroutine and the UI both need to stay responsive, and oversubscribing
        // a little phone makes the tail latency worse, not better.
        val zipformerOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(
                Runtime.getRuntime().availableProcessors().minus(1).coerceIn(1, 4),
            )
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val zipformerOrt = env.createSession(zipformerModelFile.absolutePath, zipformerOptions)
        val tokenizer = RunAwareTokenizer.fromResource()
        val finder = Finder.fromResources()
        val encoder = StreamingZipformerEncoder.create(env, zipformerOrt, zipformerMeta, tokenizer)

        // Pagination/reveal (voir app/README.md, com.tarteelwarsh.tilawa.mushaf) -
        // charges une seule fois avec le reste du pipeline, independamment
        // du mode experimental ci-dessous (le reveal ne depend pas de la
        // source du signal de coupure, seulement de PipelineResult).
        val pageIndex = PageIndex.fromAssets(context)
        val juzIndex = JuzIndex.fromAssets(context, finder)
        val basmalaIndex = BasmalaIndex.from(finder)
        val suraMetaList = SuraMetaLoader.load(context, finder)


        PipelineModels(
            encoder = encoder,
            waqfId = zipformerMeta.waqfId,
            suraNames = suraNames,
            finder = finder,
            pageIndex = pageIndex,
            juzIndex = juzIndex,
            basmalaIndex = basmalaIndex,
            suraMetaList = suraMetaList,
        )
    }

    /** Copie l'asset .onnx (materialise dans le stockage prive persistant de l'app, pas le cache - contrairement aux tests, l'app reelle ne doit pas re-copier 550 Mo a chaque redemarrage) vers un fichier reel : ORT n'accepte pas un descripteur d'asset compresse comme chemin de modele. */
    private fun materialize(context: Context, assetPath: String, fileName: String): File {
        val dest = File(context.filesDir, fileName)
        val assetSize = try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (e: java.io.IOException) {
            -1L
        }
        if (assetSize < 0 || !dest.exists() || dest.length() != assetSize) {
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
            }
        }
        return dest
    }
}

/**
 * Le "lourd" (modeles ONNX, index mushaf) charge UNE SEULE FOIS pour la vie
 * du ViewModel - PAS la session de recitation elle-meme (Corrector/
 * PipelineSession/RevealState), qui est reconstruite a CHAQUE nouvelle
 * session via `startNewSession()` (voir sa KDoc) : mode et point de depart
 * different a chaque appui sur le micro, comme Dani_app (websocket -
 * reconnexion = nouvelle `Session` Python, mode envoye une seule fois a
 * l'ouverture, jamais change ensuite - api/main.py:36, record.js:711).
 *
 * Exactement une des deux sessions est non-null apres `startNewSession()` :
 * A single `session` (ExperimentalCtcOnlyPipelineSession): the waqf-aware
 * checkpoint marks segment boundaries itself, so core-segmenter is gone and
 * there is no second pipeline to choose between. The segmenter is not
 * meme pas charge).
 */
private class PipelineModels(
    private val encoder: StreamingZipformerEncoder,
    /** Waqf symbol id from streaming_meta.json - the model's own segmentation
     * signal, which replaced core-segmenter. -1 would mean a checkpoint whose
     * vocab has no waqf token, i.e. no boundaries at all. */
    private val waqfId: Int,
    private val suraNames: List<String>,
    private val finder: Finder,
    private val pageIndex: PageIndex,
    private val juzIndex: JuzIndex,
    private val basmalaIndex: BasmalaIndex,
    val suraMetaList: List<SuraMeta>,
) {
    private var session: ExperimentalCtcOnlyPipelineSession? = null

    // Initialise a un RevealState "vide" (rien juge) pour que la navigation
    // "browse" (sura_selection) fonctionne avant meme le premier
    // `startNewSession()` - remplace par une instance FRAICHE a chaque
    // nouvelle session (voir startNewSession).
    private var revealState = RevealState(finder.oneStringUthmani, basmalaIndex)

    // Page actuellement affichee - suit le resultat le plus recent (app.js::renderResult,
    // "Follow the reciter: turn to the page this result lives on").
    private var currentPageNumber = 1


    /**
     * Demarre une session FRAICHE - appelee a CHAQUE `startListening()`
     * (jamais une reprise, voir ViewModel) : reconstruit le Corrector/la
     * session pipeline avec le MODE courant (`offset` 1="talawa"/2=sinon,
     * exactement `api/main.py:36-40` - `offset=2` declenche aussi
     * `help_tilawa`/`add_harakat`, `Session.__init__` Python) et le POINT DE
     * DEPART courant (sura, aya - miroir de `start.sura`/`start.aya`,
     * record.js:711 : la ou l'utilisateur a navigue avant d'appuyer sur le
     * micro, pas toujours 1:1). Reinitialise aussi `RevealState` (bug reel
     * trouve sur device : le reveal s'accumulait indefiniment d'une session
     * a l'autre avant cette correction - record.js::startRecording, "each
     * take is a fresh attempt, not a continuation of a previous take's
     * judgments") et l'encodeur streaming (etat Fbank/CTC propre).
     */
    fun startNewSession(mode: RecitationMode, startSura: Int, startAya: Int) {
        val offset = if (mode == RecitationMode.TALAWA) 1 else 2
        encoder.resetState()
        // One session type now: there is no segmenter to choose against. The
        // waqf-aware checkpoint marks segment boundaries itself.
        session = ExperimentalCtcOnlyPipelineSession.create(encoder, finder, waqfId, startSura, startAya, offset = offset)
        revealState = RevealState(finder.oneStringUthmani, basmalaIndex, recitationMode = mode)
        currentPageNumber = pageIndex.pageOf(startSura, startAya)
    }

    // The dual-session `?:` bug the old comment here described is gone with the
    // second session type: `session` is non-null from startNewSession onward, and
    // processChunk legitimately returns null on most chunks (no result yet), which
    // is now unambiguous.
    fun processChunk(chunk: FloatArray, timing: ChunkStageTiming, runSpeculative: Boolean = true): PipelineResult? =
        session!!.processChunk(chunk, timing, runSpeculative)

    /** One line of self-diagnosis for the UI - see MushafUiState.debugLine. */
    fun debugLine(): String {
        val s = session ?: return "no session"
        // No sil/wipes any more: the blank-duration proxy and the silence fallback
        // it fed are both gone. A committed "s" is the only thing that ends a phrase.
        return "asr=${s.lastRawLen}ch why=${s.lastBail} ctc=${encoder.text().length}ch"
    }

    /** Cloture de session (LiveCorrectionViewModel.endSession) - flush() de l'encodeur + derniere correction, voir ExperimentalCtcOnlyPipelineSession.finalizeSession(). */
    fun finalizeSession(timing: ChunkStageTiming): PipelineResult? =
        session!!.finalizeSession(timing)

    /**
     * Applique le resultat au moteur de reveal, suit la page du recitant
     * (`pageIndex.pageOf`) et reconstruit le contenu peint de la page
     * courante - miroir combine de `renderResult()` (record.js) + `goToPage`/
     * `renderPage()` (app.js).
     *
     * Bug reel trouve sur device, corrige ici : `goToPage` (record.js:602-608)
     * est place APRES le retour anticipe sur `judgedLength === 0`
     * (record.js:497-500) - tant que le corrector n'a pas "settled" (voir son
     * commentaire source : "its guess can swing wildly... surah 5 one moment,
     * surah 98 three seconds later"), ses resultats non juges (texte vide)
     * ne doivent PAS deplacer la page affichee. Ce port suivait la page sur
     * CHAQUE resultat, y compris ces suppositions non etablies - observe en
     * direct (sura=35 puis sura=93 en quelques secondes, sur un
     * enregistrement qui recitait en realite la Fatiha) avant cette
     * correction. `RevealState.applyResult()` retourne desormais ce
     * signal (false = rien de juge, ne pas suivre) plutot que d'etre ignore.
     */
    /** Current value of the session's stop counter - see utteranceEndedCount. */
    fun utteranceEndedCount(): Int = session?.utteranceEndedCount ?: 0

    /** Drop the shadow and redraw, without a PipelineResult. */
    fun finalizeShadowAndBuildPage(peekMode: Boolean): MushafPageContent? {
        if (!revealState.finalizeProvisional()) return null
        return buildPageContent(currentPageNumber, peekMode)
    }

    fun applyResultAndBuildPage(result: PipelineResult, peekMode: Boolean): MushafPageContent {
        val changed = revealState.applyResult(result)
        if (changed) currentPageNumber = pageIndex.pageOf(result.sura, result.aya)
        return buildPageContent(currentPageNumber, peekMode)
    }

    /** Repeint la page courante SANS nouveau resultat - pour le toggle "peek" (LiveCorrectionViewModel.togglePeekMode). */
    fun currentPage(peekMode: Boolean): MushafPageContent? = buildPageContent(currentPageNumber, peekMode)

    /** Any page, WITHOUT moving currentPageNumber - for the swipe neighbours. */
    fun pageAt(pageNumber: Int, peekMode: Boolean): MushafPageContent? =
        if (pageNumber < 1 || pageNumber > pageIndex.pageCount) null
        else buildPageContent(pageNumber, peekMode)

    /** Navigation "browse" (sura_selection) - independante de la recitation live, ne touche pas RevealState. */
    fun jumpToSuraForBrowsing(sura: Int, peekMode: Boolean): MushafPageContent {
        currentPageNumber = pageIndex.pageOf(sura, 1)
        return buildPageContent(currentPageNumber, peekMode)
    }

    /**
     * Navigation de page manuelle (`#prev-page`/`#next-page`, app.js) - ABSENTE
     * de ce port jusqu'ici (seul moyen de changer de page etait de reciter, ou
     * `jumpToSuraForBrowsing`). Miroir de `goToPage()` (app.js:248-251) :
     * `null` si `delta` sortirait des bornes [1, pageCount] - pas de
     * bouclage, la page ne change pas.
     */
    fun goToPage(delta: Int, peekMode: Boolean): MushafPageContent? {
        val target = currentPageNumber + delta
        if (target < 1 || target > pageIndex.pageCount) return null
        currentPageNumber = target
        return buildPageContent(currentPageNumber, peekMode)
    }

    /** Miroir exact de `firstAyahOfCurrentPage()` (record.js:616-619) - la position de depart d'une NOUVELLE session suit la page actuellement affichee, pas un point separement memorise. */
    fun firstAyahOfCurrentPage(): Pair<Int, Int> {
        val seg = pageIndex.segments(currentPageNumber).first()
        return seg.sura to seg.from
    }

    fun revealCounts() = revealState.counts()

    /**
     * Miroir fidele de `buildPageContent()` (app.js) : boucle sur CHAQUE
     * segment de la page, pas seulement le premier - une page peut porter
     * la fin d'une sourate courte ET le debut d'une autre (ex: Al-Layl puis
     * Ad-Duha), auquel cas CHACUNE recoit son cadre + sa basmala. Bug reel
     * trouve sur device (seul le premier segment de la page en recevait un)
     * avant cette correction.
     */
    private fun buildPageContent(pageNumber: Int, peekMode: Boolean): MushafPageContent {
        val segments = pageIndex.segments(pageNumber)
        val firstSeg = segments.first()
        val text = finder.oneStringUthmani
        val juz = juzIndex.juzAt(finder.ayaRangeUthmani(firstSeg.sura, firstSeg.from).start)

        val items = mutableListOf<MushafPageItem>()
        segments.forEach { seg ->
            val basmala = if (seg.from == 1) basmalaIndex.boundsFor(seg.sura) else null
            if (seg.from == 1) {
                items.add(MushafPageItem.SuraHeader("سورة ${suraName(seg.sura)}"))
                if (basmala != null) {
                    items.add(MushafPageItem.Basmala(MushafPaint.paintRange(revealState, text, basmala.start, basmala.end, peekMode)))
                }
            }
            for (a in seg.from..seg.to) {
                val range = finder.ayaRangeUthmani(seg.sura, a)
                // L'ayah 1 ne porte que son texte propre ici - le prefixe
                // basmala est deja rendu separement au-dessus. app.js::buildPageContent.
                val from = if (a == 1 && basmala != null) basmala.restStart else range.start
                val runs = MushafPaint.paintRange(revealState, text, from, range.end, peekMode)
                items.add(MushafPageItem.Ayah(seg.sura, a, toArabicNumeral(a), runs))
            }
        }

        return MushafPageContent(
            pageNumber = pageNumber,
            pageNumberLabel = toArabicNumeral(pageNumber),
            pageCount = pageIndex.pageCount,
            suraName = suraName(firstSeg.sura),
            juzLabel = "الجزء ${JUZ_ORDINALS.getOrElse(juz - 1) { juz.toString() }}",
            items = items,
        )
    }

    /** Diagnostic UNIQUEMENT - longueur du texte decode courant. */
    fun debugCtcState(): String? = session?.let { "text.len=${encoder.text().length}" }

    /** sura est 1-indexe (comme partout dans core-logic/core-pipeline) - suraNames est 0-indexe. */
    fun suraName(sura: Int): String = suraNames.getOrElse(sura - 1) { "سورة $sura" }

    fun close() {
        encoder.close()
    }

    private companion object {
        // Chiffres arabes-indiens + ordinaux de juz' - copies verbatim de
        // EASTERN_ARABIC_DIGITS/JUZ_ORDINALS (Dani_app/web/app.js:14-36).
        val EASTERN_ARABIC_DIGITS = listOf("٠", "١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩")
        val JUZ_ORDINALS = listOf(
            "الأول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر",
            "الحادي عشر", "الثاني عشر", "الثالث عشر", "الرابع عشر", "الخامس عشر", "السادس عشر", "السابع عشر", "الثامن عشر", "التاسع عشر", "العشرون",
            "الحادي والعشرون", "الثاني والعشرون", "الثالث والعشرون", "الرابع والعشرون", "الخامس والعشرون", "السادس والعشرون", "السابع والعشرون", "الثامن والعشرون", "التاسع والعشرون", "الثلاثون",
        )

        fun toArabicNumeral(n: Int): String = n.toString().map { EASTERN_ARABIC_DIGITS[it - '0'] }.joinToString("")
    }
}

/**
 * Set `page`, and refresh the swipe neighbours only when the page NUMBER actually
 * changed. Building a page is not free, and a correction arrives several times a
 * second while the page usually stays put.
 */
private fun MushafUiState.withPage(page: MushafPageContent, loaded: PipelineModels): MushafUiState =
    if (this.page?.pageNumber == page.pageNumber) copy(page = page)
    else copy(
        page = page,
        prevPage = loaded.pageAt(page.pageNumber - 1, peekMode),
        nextPage = loaded.pageAt(page.pageNumber + 1, peekMode),
    )
