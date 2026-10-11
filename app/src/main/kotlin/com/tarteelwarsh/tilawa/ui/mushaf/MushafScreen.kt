package com.tarteelwarsh.tilawa.ui.mushaf

import com.tarteelwarsh.tilawa.BuildConfig
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tarteelwarsh.tilawa.mushaf.PaintState
import com.tarteelwarsh.tilawa.ui.theme.AppIcons
import com.tarteelwarsh.tilawa.ui.theme.TilawaColors
import com.tarteelwarsh.tilawa.ui.theme.TilawaShapes
import com.tarteelwarsh.tilawa.ui.theme.TilawaSpacing
import com.tarteelwarsh.tilawa.ui.theme.TilawaType
import com.tarteelwarsh.tilawa.ui.theme.TilawaWarshTheme
import kotlin.math.abs
import kotlin.math.round
import kotlinx.coroutines.launch

/**
 * Port fidele de stitch_tarteel_warsh_ai_teacher/revealed_mode (style
 * visuel : pastille "glass-card", page mushaf, bulles de verset dorees)
 * COMBINE avec la logique de reveal/pagination REELLE de Dani_app
 * (Dani_app/web/app.js + record.js, via core `PageIndex`/`BasmalaIndex`/
 * `RevealState`/`MushafPaint`) - remplace `ui.livecorrection.LiveCorrectionScreen`
 * comme ecran principal (voir app/README.md : celui-ci suivait un mockup
 * clair/carte compacte qui ne correspond pas au vrai Dani_app).
 *
 * Ecart DELIBERE et documente vs la description prose donnee en amont de ce
 * portage ("le texte a venir completement absent, la mise en page se
 * redessine") : le VRAI CSS de Dani_app (`style.css::.unrevealed`,
 * commentaire source explicite) garde l'espace de mise en page reserve
 * (`visibility: hidden`) plutot que de retirer le texte - "fully hidden
 * rather than blurred..., but keeps its layout space so text doesn't jump
 * around as it's revealed." Suivi ici a la lettre (couleur transparente,
 * meme empreinte) conformement a la consigne explicite de preferer le code
 * source reel a toute description anterieure en cas de divergence.
 */
@Composable
fun MushafScreen(
    state: MushafUiState,
    onMicClick: () -> Unit,
    onPeekToggle: () -> Unit,
    onTuneClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onBrowseClick: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onFrameDisplayed: (resultId: Long, latencyMs: Double) -> Unit = { _, _ -> },
) {
    // The screen must not sleep mid-recitation. The reciter is looking at the
    // page and holding no touchscreen, so Android's idle timer has nothing to see
    // and dims as if the phone were abandoned. Scoped to isListening and cleared
    // in onDispose, so it can never leak into normal browsing.
    val view = LocalView.current
    DisposableEffect(state.isListening) {
        view.keepScreenOn = state.isListening
        onDispose { view.keepScreenOn = false }
    }

    // Mesure de latence son -> affichage REELLE - voir LiveCorrectionScreen
    // (meme mecanisme withFrameNanos, inchange par ce portage).
    LaunchedEffect(state.resultId) {
        val capturedAtNanos = state.capturedAtNanos ?: return@LaunchedEffect
        withFrameNanos { frameTimeNanos ->
            onFrameDisplayed(state.resultId, (frameTimeNanos - capturedAtNanos) / 1_000_000.0)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            containerColor = TilawaColors.Background,
            topBar = { MushafTopBar(onMenuClick = onBrowseClick, onSearchClick = onBrowseClick) },
        ) { padding ->
            // Miroir de la structure DOM reelle `.reader` (index.html:46-87) :
            // header (fixe) / .page-area (flex:1, PAS de scroll propre) /
            // footer.bottom-bar (fixe) - trois enfants de Column EMPILES, pas
            // un overlay. Ecart corrige vs la version precedente de ce port,
            // qui faisait defiler TOUT l'ecran (pastille+page+compteur de
            // confiance+badge latence confondus) - bug reel signale par
            // l'utilisateur : le vrai Dani_app n'exige jamais de defiler pour
            // voir un verset, la page entiere tient dans le cadre des le
            // depart (`.page-frame { height:100%; max-height:880px }` a
            // l'interieur de `.page-area { flex:1 }`, style.css:417-433).
            Column(modifier = Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                // Dev metrics are OFF by default and revealed by LONG-PRESSING the
                // sura/juz pill. Deliberately undiscoverable: on a phone screen the
                // readout costs several lines that the mushaf text needs, and the
                // "retard N chunks" field appears and disappears between frames,
                // which made the whole page jump. Hidden rather than removed
                // because it is what diagnosed the waqf and backlog faults.
                // The trust bar is NOT covered by this - it stays always visible.
                var showDevMetrics by rememberSaveable { mutableStateOf(false) }

                val page = state.page
                if (page != null) {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(onLongPress = { showDevMetrics = !showDevMetrics })
                        },
                    ) {
                        SuraJuzPill(suraName = page.suraName, juzLabel = page.juzLabel)
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (page != null) {
                        // Navigation par glissement horizontal, PAS par boutons
                        // (`#prev-page`/`#next-page`) - miroir exact de la regle
                        // mobile reelle de Dani_app : "Swipe (see app.js) covers
                        // page navigation on touch devices now, so the prev/next
                        // circles are just redundant clutter over the text on
                        // mobile" (style.css:630-634, `.page-nav { display:none }`
                        // sous @media max-width:700px). Boutons ‹/› retires ici,
                        // pas seulement caches - jamais rendus sur un ecran de
                        // telephone, exactement comme cote Dani_app.
                        SwipeablePage(
                            debugLine = if (showDevMetrics) state.debugLine else null,
                            page = page,
                            prevPage = state.prevPage,
                            nextPage = state.nextPage,
                            onPreviousPage = onPreviousPage,
                            onNextPage = onNextPage,
                        )
                    } else if (state.isLoadingModels) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = TilawaColors.Primary)
                            Spacer(Modifier.height(12.dp))
                            Text("تحميل النماذج...", style = TilawaType.BodyLg, color = TilawaColors.OnSurfaceVariant)
                        }
                    }
                }
                // Accueil premier lancement - defaut d'onboarding reel signale
                // par l'utilisateur (l'ecran vide avant tout tap sur le micro
                // n'offrait aucune indication). Affiche des qu'une page existe
                // et que rien n'est en cours (idle) - pas seulement "jamais
                // ecoute une fois", un rappel discret reste utile entre deux
                // sessions aussi. Chrome FIXE (hors de la zone de page qui
                // occupe deja tout l'espace vertical disponible via weight(1f)).
                if (page != null && !state.isListening && !state.isFinalizing) {
                    Text(
                        "اضغط على المیكروفون لبدء التلاوة والتصحيح المباشر",
                        style = TilawaType.LabelSm,
                        color = TilawaColors.OnSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = TilawaSpacing.ContainerPadding, vertical = 6.dp),
                    )
                }
                // Diagnostic DEV uniquement - bug reel trouve sur device : ce badge
                // etait visible INCONDITIONNELLEMENT (y compris en release), du texte
                // technique ("calcul 409ms · son->affichage 152279ms") jamais destine
                // a un utilisateur final. `BuildConfig.DEBUG` le confine desormais aux
                // builds de developpement - toujours utile pour mesurer la latence
                // reelle pendant le developpement (voir app/README.md), jamais montre
                // en production.
                if (showDevMetrics && BuildConfig.DEBUG && (state.computeLatencyMs != null || state.latencyMs != null)) {
                    LatencyBadge(state.computeLatencyMs, state.latencyMs, state.backlogChunks)
                    Spacer(Modifier.height(6.dp))
                }
                MushafBottomController(
                    isListening = state.isListening,
                    isFinalizing = state.isFinalizing,
                    isLoadingModels = state.isLoadingModels,
                    peekMode = state.peekMode,
                    trust = state.trust,
                    onMicClick = onMicClick,
                    onPeekToggle = onPeekToggle,
                    onTuneClick = onTuneClick,
                    onHistoryClick = onHistoryClick,
                )
            }
        }
    }
}

/**
 * Zone de page glissable horizontalement - adaptation Android du swipe reel
 * de Dani_app (`app.js` : `DRAG_INTENT_PX`/`DRAG_COMMIT_FRACTION`/
 * `DRAG_EDGE_RESISTANCE`, `touchstart`/`touchmove`/`touchend` sur
 * `.page-area`). Meme mapping de direction (glisser vers la DROITE physique
 * de l'ecran = page SUIVANTE - reliure a droite, sens de lecture arabe,
 * commentaire source exact : "left-to-right (dx>0) advances"), meme seuil de
 * validation (28% de la largeur du viewport) et meme resistance en bordure
 * (35%, premiere/derniere page). `detectHorizontalDragGestures` gere deja
 * nativement la distinction geste horizontal/vertical (cede a un defilement
 * vertical si le mouvement est majoritairement vertical), miroir fonctionnel
 * de la comparaison `Math.abs(dy) >= Math.abs(dx)` faite a la main cote JS.
 *
 * Comme le web (`.page-preview` pre-rendue et glissee en meme temps que
 * `.page-content` sort), DEUX pages bougent sous le doigt. Ce port ne le
 * faisait pas - "simplification assumee" - et on voyait donc le fond noir dans
 * la moitie liberee au lieu de la page vers laquelle on tourne. Les voisines
 * (`prevPage`/`nextPage`) ne sont composees que PENDANT le geste : au repos on
 * revient a une seule page, ce qui compte sur un telephone deja juste en calcul.
 */
/**
 * A neighbouring page as it appears during a swipe: same text and page number,
 * but no scrolling, no auto-scroll reporting and no gesture handling - it is
 * scenery, and must never claim the scroll follow that belongs to the live page.
 */
@Composable
private fun PagePreview(page: MushafPageContent, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = TilawaSpacing.ContainerPadding, vertical = 8.dp),
        ) {
            QuranPageBody(page, autoScroll = null)
        }
        Text(
            page.pageNumberLabel,
            style = TilawaType.LabelSm,
            color = TilawaColors.OnSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun SwipeablePage(
    page: MushafPageContent,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    debugLine: String? = null,
    prevPage: MushafPageContent? = null,
    nextPage: MushafPageContent? = null,
) {
    val offsetX = remember { Animatable(0f) }
    var viewportWidthPx by remember { mutableStateOf(0f) }
    // Fige au 1er increment du geste (miroir de `dragTargetPage`, decide une
    // seule fois par glissement cote app.js) : -1 precedente, +1 suivante.
    var dragDirection by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportWidthPx = it.width.toFloat() }
            .pointerInput(page.pageNumber, page.pageCount) {
                detectHorizontalDragGestures(
                    onDragStart = { dragDirection = 0 },
                    onDragEnd = {
                        val committed = viewportWidthPx > 0 &&
                            abs(offsetX.value) > viewportWidthPx * SWIPE_COMMIT_FRACTION
                        val direction = dragDirection
                        val canTurn = (direction > 0 && page.pageNumber < page.pageCount) ||
                            (direction < 0 && page.pageNumber > 1)

                        if (committed && canTurn) {
                            // Carry the gesture THROUGH instead of snapping back.
                            // Previously the slide-to-0 animation was launched
                            // unconditionally and ran at the same time as the page
                            // swap, so the new page animated in from the side the
                            // finger came from - the turn appeared to go backwards.
                            // Now: finish sliding the old page out the way it was
                            // going, swap, place the new page just off the opposite
                            // edge, and bring it in.
                            scope.launch {
                                val out = if (direction > 0) viewportWidthPx else -viewportWidthPx
                                offsetX.animateTo(out, tween(160))
                                if (direction > 0) onNextPage() else onPreviousPage()
                                offsetX.snapTo(-out)
                                offsetX.animateTo(0f, tween(220))
                            }
                        } else {
                            scope.launch { offsetX.animateTo(0f, tween(200)) }
                        }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f, tween(200)) } },
                ) { change, dragAmount ->
                    if (dragDirection == 0) dragDirection = if (dragAmount > 0) 1 else -1
                    val atEdge = (dragDirection > 0 && page.pageNumber >= page.pageCount) ||
                        (dragDirection < 0 && page.pageNumber <= 1)
                    val effective = if (atEdge) dragAmount * SWIPE_EDGE_RESISTANCE else dragAmount
                    change.consume()
                    scope.launch { offsetX.snapTo(offsetX.value + effective) }
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        // Neighbours, so a half-turned page shows the page you are turning TO
        // rather than the background. Composed ONLY mid-gesture: idle, this is a
        // single page again, which matters on a phone that is already tight on
        // compute. Dragging right turns to the NEXT page, so the next page waits
        // one viewport to the left, and the previous one to the right.
        if (offsetX.value != 0f && viewportWidthPx > 0f) {
            nextPage?.let {
                PagePreview(it, Modifier.graphicsLayer { translationX = offsetX.value - viewportWidthPx })
            }
            prevPage?.let {
                PagePreview(it, Modifier.graphicsLayer { translationX = offsetX.value + viewportWidthPx })
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX.value },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val pageScroll = rememberScrollState()
            val autoScroll = remember { PageAutoScroll() }
            // Keyed on the frontier's position within the text, NOT on the scroll
            // position - so scrolling cannot retrigger this and chase itself.
            // A new page starts at its beginning. rememberScrollState survives the page
            // swap (same composable identity), so without this the new page opened
            // scrolled to wherever the previous one had been left. scrollTo, not
            // animateScrollTo: the page itself already changed, so animating the
            // scroll on top of that reads as a glitch. frontierInText is cleared so
            // the auto-scroll below cannot immediately drag the view back down using a
            // measurement taken on the OLD page.
            LaunchedEffect(page.pageNumber) {
                autoScroll.clear()
                pageScroll.scrollTo(0)
            }
            LaunchedEffect(page.pageNumber, autoScroll.frontierInContent, autoScroll.viewportHeight, pageScroll.maxValue) {
                val inContent = autoScroll.frontierInContent ?: return@LaunchedEffect
                if (autoScroll.viewportHeight == 0 || pageScroll.maxValue == 0) return@LaunchedEffect
                val desired = (inContent - autoScroll.viewportHeight / 2f)
                    .toInt().coerceIn(0, pageScroll.maxValue)
                // Threshold so we never fight the user over a few pixels.
                if (kotlin.math.abs(desired - pageScroll.value) > 8) pageScroll.animateScrollTo(desired)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // BEFORE verticalScroll: this must measure the VIEWPORT. Placed
                    // after, the node lives inside the scroll and reports the content
                    // height instead - which is what broke the first attempt.
                    .onGloballyPositioned { autoScroll.viewportHeight = it.size.height }
                    .verticalScroll(pageScroll) // miroir de `.page-content { overflow-y: auto }` (style.css:454) - desormais pilote, voir PageAutoScroll.
                    .padding(horizontal = TilawaSpacing.ContainerPadding, vertical = 8.dp),
            ) {
                QuranPageBody(page, autoScroll)
            }
            // `#page-number` (index.html:68) : SOUS le contenu de page, a
            // l'interieur du cadre - pas au-dessus dans une rangee de
            // navigation (ancienne position, retiree avec `PageNavRow`).
            Text(
                page.pageNumberLabel,
                style = TilawaType.LabelSm,
                color = TilawaColors.OnSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            // Temporary self-diagnosis, visible only while recording. Remove once the
            // silent-failure bug is closed.
            if (debugLine != null) {
                Text(
                    debugLine,
                    style = TilawaType.LabelSm,
                    color = TilawaColors.Primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

/**
 * Keeps the reciter's current position centred in the page viewport.
 *
 * The page body always had `.verticalScroll(...)`, but its comment called it "a
 * safety net, never exercised in practice at calibrated font size" - the page was
 * assumed to always fit. It does not on every screen, and nothing drove the
 * scroll, so the app changed page but never followed within one. Equivalent of
 * record.js:650 `ayahEl.scrollIntoView({ block: "center", behavior: "smooth" })`.
 *
 * Everything here is SCROLL-INVARIANT, which a root-coordinate target is not:
 * `textTop` and `contentTop` both move by the same amount when the view scrolls,
 * so their difference is the text block's fixed offset inside the content. The
 * desired scroll is then absolute and can be recomputed at any time without
 * feeding back on itself:
 *
 *     desired = (textTop - contentTop) + offsetOfFrontierWithinText - viewport/2
 *
 * `viewportHeight` must be measured OUTSIDE the scroll modifier - measured inside,
 * it reports the content height instead, which was the original defect here.
 */
private class PageAutoScroll {
    /** Viewport height, measured outside verticalScroll. */
    var viewportHeight by mutableStateOf(0)
    /** Root Y of the scrollable CONTENT root - moves as we scroll. */
    var contentTop by mutableStateOf(0f)

    // Per-BLOCK, keyed by position in the page. QuranPageBody emits one block per
    // run of consecutive ayahs - it flushes a new one at every sura header and
    // every basmala - so a page is frequently several blocks, and each reports
    // its geometry through two different callbacks that fire at different times
    // (onGloballyPositioned for the top, onTextLayout for the frontier).
    //
    // Sharing ONE textTop/frontierInText pair across all of them meant the top of
    // whichever block was positioned last got combined with an offset measured
    // inside whichever block laid out last. On a multi-block page those are
    // different blocks, so the target belonged to neither and the view jumped to
    // the middle of the page instead of following the recitation. Keying both by
    // block index means only values from the SAME block are ever combined.
    private val tops = mutableStateMapOf<Int, Float>()
    private val frontiers = mutableStateMapOf<Int, Float>()

    fun reportTop(block: Int, y: Float) {
        tops[block] = y
    }

    fun reportFrontier(block: Int, yInText: Float?) {
        if (yInText == null) frontiers.remove(block) else frontiers[block] = yInText
    }

    fun clear() {
        tops.clear()
        frontiers.clear()
    }

    /**
     * Offset of the reciting frontier within the scrollable content, or null when
     * nothing on this page has been recited yet.
     *
     * The LAST block holding a frontier wins: once part of a page is revealed
     * several blocks can contain judged text, and the reciter is in the furthest
     * one down.
     */
    val frontierInContent: Float?
        get() {
            val block = frontiers.keys.maxOrNull() ?: return null
            val top = tops[block] ?: return null
            val inText = frontiers[block] ?: return null
            return (top - contentTop) + inText
        }
}

private const val SWIPE_COMMIT_FRACTION = 0.28f
private const val SWIPE_EDGE_RESISTANCE = 0.35f

/** Identique a l'ancienne TilawaTopBar (LiveCorrectionScreen) - meme mockup, meme ordre DOM verifie. ☰/🔍 desormais REELLEMENT relies (`onMenuClick`/`onSearchClick`, tous deux vers la liste des sourates - miroir de `sidebar-toggle`, index.html:48, qui ouvre la meme sidebar que la recherche). */
@Composable
private fun MushafTopBar(onMenuClick: () -> Unit, onSearchClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TilawaColors.Surface)
            .padding(horizontal = 4.dp)
            // 40dp, down from 56: on a phone the mushaf page is the screen's whole
            // job and the bar was taking a line of verse away from it.
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onMenuClick) {
            Icon(Icons.Filled.Menu, contentDescription = null, tint = TilawaColors.Primary)
        }
        Text("الدَّانِي", style = TilawaType.HeadlineMd, color = TilawaColors.Primary)
        IconButton(onClick = onSearchClick) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = TilawaColors.Primary)
        }
    }
}

/** Miroir de `#trust-meter`/`updateTrustMeter()` (index.html:73-77, record.js) - `trustToColor` : gris a 0 -> ambre a 3 -> vert a 6 (commentaire source exact), roles semantiques deja actes plutot que les valeurs RGB litterales de record.js. Deplace dans `MushafBottomController` (footer FIXE) - le vrai `#trust-meter` est un enfant de `footer.bottom-bar` (index.html:72-77), jamais melange au contenu de page defilable. */
@Composable
private fun TrustMeter(trust: Double) {
    val clamped = trust.coerceIn(0.0, 6.0)
    val fraction = (clamped / 6.0).toFloat()
    val color = when {
        clamped < 3.0 -> lerpColor(TilawaColors.OutlineVariant, TilawaColors.FeedbackDuration, (clamped / 3.0).toFloat())
        else -> lerpColor(TilawaColors.FeedbackDuration, TilawaColors.FeedbackCorrect, ((clamped - 3.0) / 3.0).toFloat())
    }
    Row(
        modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(String.format(java.util.Locale.US, "%.1f", clamped), style = TilawaType.LabelSm, color = color)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(TilawaShapes.Full)
                .background(TilawaColors.SurfaceContainerHigh),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(TilawaShapes.Full)
                    .background(color),
            )
        }
    }
}

private fun lerpColor(from: Color, to: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * f,
        green = from.green + (to.green - from.green) * f,
        blue = from.blue + (to.blue - from.blue) * f,
        alpha = 1f,
    )
}

/**
 * Pastille "glass-card" (revealed_mode/code.html) - contenu = sourate + juz'
 * (app.js::renderPage, infoSurahEl/infoJuzEl), PAS mecquois/nombre de
 * versets comme le mockup Stitch : ecart DELIBERE, le vrai Dani_app affiche
 * sourate+juz' dans cette pastille (verifie sur capture d'ecran reelle),
 * mecquois/nombre de versets n'y figurent nulle part cote Dani_app.
 */
@Composable
private fun SuraJuzPill(suraName: String, juzLabel: String) {
    Row(
        modifier = Modifier
            .clip(TilawaShapes.Full)
            .background(TilawaColors.SurfaceContainerHigh.copy(alpha = 0.6f))
            .border(1.dp, TilawaColors.MushafAccent.copy(alpha = 0.15f), TilawaShapes.Full)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(suraName, style = TilawaType.HeadlineMd, color = TilawaColors.MushafAccent)
        Text("|", style = TilawaType.BodyLg, color = TilawaColors.OnSurfaceVariant)
        Text(juzLabel, style = TilawaType.LabelSm, color = TilawaColors.OnSurfaceVariant)
    }
}

/** Miroir exact de `.surah-header` (Dani_app/web/style.css:525-533). */
@Composable
private fun SurahHeaderFrame(text: String) {
    Text(
        text = text,
        style = TilawaType.HeadlineLg.copy(fontSize = 24.sp),
        color = TilawaColors.MushafAccent,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TilawaColors.MushafAccent, RoundedCornerShape(8.dp))
            .padding(vertical = 6.dp),
    )
}

/**
 * Miroir de `.basmala` (Dani_app/web/style.css:561-565) - deja peinte
 * (masquable/coloree par jugement comme tout le reste, voir RevealState).
 * `.basmala` n'a PAS de regle de couleur propre cote Dani_app (peinte par la
 * MEME `paintRange()` que le reste du texte, dans un conteneur different) :
 * pas de teinte doree fixe ici non plus, malgre le mockup Stitch
 * (`revealed_mode/code.html`) qui la dore artificiellement (`text-primary-fixed-dim`)
 * faute de donnees de jugement reelles a afficher dans une preview statique.
 */
@Composable
private fun BasmalaLine(runs: List<com.tarteelwarsh.tilawa.mushaf.PaintRun>) {
    val colors = rememberMushafRunColors()
    val annotated = remember(runs, colors) {
        buildAnnotatedString {
            runs.forEach { run ->
                withStyle(SpanStyle(color = mushafRunColor(run.state, colors), textDecoration = mushafRunDecoration(run.state))) {
                    append(run.text)
                }
            }
        }
    }
    Text(annotated, style = TilawaType.QuranText, textAlign = TextAlign.Center, color = TilawaColors.OnBackground)
}

/**
 * Corps de page complet : suit l'ordre DOM EXACT de `buildPageContent()`
 * (app.js) - cadre de sourate / basmala sont des blocs SEPARES (`display:
 * block` cote CSS, une ligne a eux), mais des `Ayah` CONSECUTIFS (y compris
 * a cheval sur deux sourates courtes de la meme page) restent dans le MEME
 * paragraphe qui s'enchaine, comme le flux DOM reel (`.ayah`/`.ayah-number`
 * simples enfants inline de `#page-content`, pas un conteneur par verset).
 * Bug reel trouve sur device, corrige ici : une page peut porter la fin
 * d'une sourate ET le debut d'une autre (Al-Layl -> Ad-Duha) - CHAQUE
 * segment `seg.from == 1` doit recevoir son cadre + sa basmala, pas
 * seulement le premier de la page.
 */
@Composable
private fun QuranPageBody(page: MushafPageContent, autoScroll: PageAutoScroll? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // Inside the scroll, so this moves as we scroll - which is the point:
        // (textTop - contentTop) is then a scroll-invariant offset. See PageAutoScroll.
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (autoScroll == null) Modifier
                else Modifier.onGloballyPositioned { autoScroll.contentTop = it.positionInRoot().y },
            ),
    ) {
        var ayahRun = mutableListOf<MushafPageItem.Ayah>()
        // Position of each emitted text block in the page. page.items is fixed for
        // a given page, so the same block gets the same index on every
        // recomposition - which is what lets PageAutoScroll pair a block's top
        // with a frontier measured inside that same block.
        var blockIndex = 0

        @Composable
        fun flushAyahRun() {
            if (ayahRun.isEmpty()) return
            QuranAyahFlow(ayahRun, autoScroll, blockIndex++)
            ayahRun = mutableListOf()
        }

        page.items.forEach { item ->
            when (item) {
                is MushafPageItem.Ayah -> ayahRun.add(item)
                is MushafPageItem.SuraHeader -> {
                    flushAyahRun()
                    SurahHeaderFrame(item.text)
                    Spacer(Modifier.height(4.dp))
                }
                is MushafPageItem.Basmala -> {
                    flushAyahRun()
                    BasmalaLine(item.runs)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        flushAyahRun()
    }
}

/**
 * Un groupe de versets consecutifs (voir QuranPageBody), en UN SEUL `Text`/
 * `AnnotatedString` par groupe avec `InlineTextContent` pour les bulles de
 * verset - PAS un `FlowRow` mot-par-mot (approche precedente, elle-meme un
 * remplacement d'une version encore anterieure qui utilisait deja
 * `InlineTextContent` mais avec `TextAlign.Justify`).
 *
 * Historique des DEUX bugs reels trouves sur device dans cette meme zone :
 * 1) `InlineTextContent`+`Justify` (version originale) : les bulles dorees
 *    pouvaient se retrouver ecrasees/superposees au texte voisin - `Justify`
 *    peut comprimer un `Placeholder` SOUS sa largeur declaree quand une
 *    ligne est presque pleine (confirme empiriquement : agrandir la bulle
 *    deplacait juste le probleme d'un verset a l'autre - Ar-Rahman, ayah 2
 *    -> ayah 51). Contrairement a `inline-flex` en CSS (vrai comportement du
 *    navigateur pour `.ayah-number`, meme avec `text-align:justify` reel
 *    cote Dani_app, style.css:459), Compose ne protege pas nativement contre
 *    ca.
 * 2) `FlowRow` mot-par-mot (version intermediaire, tentative de fix du bug
 *    1) : PIRE bug trouve ensuite sur device - texte "eparpille" de facon
 *    chaotique pendant une correction active (capture d'ecran fournie par
 *    l'utilisateur, sourate Al-Imran ~19-21). Root-cause : `FlowRow` mesure
 *    et fait passer a la ligne CHAQUE mot comme un widget independant,
 *    contrairement au moteur de paragraphe reel (navigateur OU `Text` de
 *    Compose) qui calcule les sauts de ligne sur TOUT le texte (visible et
 *    masque) comme un flux continu. Or une grande partie d'une page peut
 *    etre `Unrevealed` (transparent, espace garde - voir `mushafRunColor`) :
 *    de longues suites de "mots" invisibles dans `FlowRow` creaient de
 *    larges vides visuels, avec pour effet qu'un court fragment REELLEMENT
 *    visible se retrouvait seul sur sa ligne (le `Arrangement.spacedBy` fixe
 *    de `FlowRow` n'a rien a voir avec le calcul de justification/reflow
 *    d'un vrai paragraphe) - lisible comme "desordre" par l'oeil, meme si
 *    l'ordre de lecture sous-jacent restait techniquement correct.
 *
 * Fix retenu : revenir a `InlineTextContent` (un seul `Text` par groupe,
 * reflow de paragraphe REEL sur le texte entier y compris les portions
 * masquees - un fragment visible reste a sa position de lecture naturelle,
 * plus de vides "FlowRow") mais SANS `Justify` (`TextAlign.Right`, ecart
 * DELIBERE et ASSUME vs le vrai CSS Dani_app qui justifie - lignes non
 * etirees a la largeur totale) : elimine le bug 1 a la racine (rien a
 * comprimer sans justification a satisfaire) sans reintroduire le bug 2.
 */
@Composable
private fun QuranAyahFlow(ayahs: List<MushafPageItem.Ayah>, autoScroll: PageAutoScroll? = null, block: Int = 0) {
    val colors = rememberMushafRunColors()
    val density = LocalDensity.current
    val badgeSizeSp = remember(density) { with(density) { 28.dp.toSp() } }

    val inlineContent = remember(ayahs, badgeSizeSp) {
        ayahs.associate { ayah ->
            inlineBadgeId(ayah) to InlineTextContent(
                Placeholder(width = badgeSizeSp, height = badgeSizeSp, placeholderVerticalAlign = PlaceholderVerticalAlign.Center),
            ) { AyahNumberBadge(ayah.numberLabel) }
        }
    }
    // Character offset of the LAST judged character in the whole block - the
    // reciting frontier. Using the frontier rather than the ayah start means the
    // view follows within a long ayah too, which record.js cannot do (it can only
    // scrollIntoView a whole element).
    // Both values come out of ONE remember. Computing the frontier in a plain local
    // mutated inside the remember lambda would leave it stale: remember returns the
    // cached AnnotatedString without re-running the block, so the frontier would
    // stay -1 on every recomposition after the first.
    val (annotated, frontierOffset) = remember(ayahs, colors) {
        var frontier = -1
        val built = buildAnnotatedString {
            ayahs.forEachIndexed { index, ayah ->
                ayah.runs.forEach { run ->
                    // Unrevealed text is not yet reached; anything Judged has been -
                    // EXCEPT whitespace, which is always Judged no matter where the
                    // reciter is. MushafPaint.paintRange:40 paints every space as
                    // Judged(color=1) on purpose, because a masked space looks
                    // identical to a visible one and there is nothing to hide.
                    //
                    // Without the isNotBlank() guard this scan walked straight
                    // through the unrecited text on the strength of its spaces and
                    // parked the frontier on the LAST space in the block. The scroll
                    // target was then effectively fixed near the bottom of the page
                    // and never followed the recitation - the reported "it goes to
                    // the middle of the page" - because a space carries no
                    // information about progress. Only a real recited character does.
                    val st = run.state
                    if (st is PaintState.Judged && st.reached && run.text.isNotBlank()) {
                        frontier = length + run.text.length - 1
                    }
                    withStyle(SpanStyle(color = mushafRunColor(run.state, colors), textDecoration = mushafRunDecoration(run.state))) {
                        append(run.text)
                    }
                }
                append(' ')
                appendInlineContent(inlineBadgeId(ayah), "￼")
                if (index != ayahs.lastIndex) append(' ')
            }
        }
        built to frontier
    }
    // BASELINE of every laid-out line, not getLineBottom(). The ruling of a real
    // mushaf is the line the letters SIT on, so descenders cross it: the tail of
    // و passes through, and the dot of ب falls below. getLineBottom() sits under
    // the whole descent, which left every letter floating above the rule.
    //
    // Taken from the SAME TextLayoutResult the scroll follow uses, so the ruling
    // can never drift out of step with the glyphs the way a separately computed
    // line height would.
    var lineBaselines by remember(annotated) { mutableStateOf(FloatArray(0)) }
    val ruleColor = TilawaColors.MushafRule

    Text(
        text = annotated,
        style = TilawaType.QuranText,
        color = TilawaColors.OnBackground,
        textAlign = TextAlign.Center,
        inlineContent = inlineContent,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // Full-width hairline ON each line's baseline, like the ruling of a
                // printed mushaf. Behind the text, so glyphs always win.
                for (y in lineBaselines) {
                    drawLine(
                        color = ruleColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f, // 1 physical pixel, not 1.dp - a true hairline
                    )
                }
            }
            .then(
                if (autoScroll == null) Modifier
                else Modifier.onGloballyPositioned { autoScroll.reportTop(block, it.positionInRoot().y) },
            ),
        onTextLayout = { layout ->
            lineBaselines = FloatArray(layout.lineCount) { layout.getLineBaseline(it) }

            // Reported under THIS block's index, never into a field shared with the
            // other blocks on the page - see PageAutoScroll.
            //
            // Only the offset WITHIN this text block, so it changes on relayout and
            // not on scroll. getBoundingBox is per-character, so the view follows
            // the line actually being recited - including within a long ayah, which
            // scrollIntoView on the web cannot do.
            autoScroll?.reportFrontier(
                block,
                if (frontierOffset in 0 until annotated.length) {
                    layout.getBoundingBox(frontierOffset).top
                } else {
                    null // nothing judged in this block - it does not claim the view
                },
            )
        },
    )
}

private fun inlineBadgeId(ayah: MushafPageItem.Ayah) = "badge_${ayah.sura}_${ayah.aya}"

private fun mushafRunColor(state: PaintState, colors: MushafRunColors): Color = when (state) {
    is PaintState.Unrevealed -> Color.Transparent // .unrevealed { visibility: hidden } - espace garde, invisible.
    is PaintState.Judged -> {
        val base = mushafColorForCode(state.color, colors)
        if (state.provisional) base.copy(alpha = base.alpha * 0.5f) else base // .provisional { opacity: 0.5 }
    }
}

private fun mushafRunDecoration(state: PaintState): TextDecoration = when (state) {
    is PaintState.Unrevealed -> TextDecoration.None
    is PaintState.Judged -> if (isMushafUnderlinedCode(state.color)) TextDecoration.Underline else TextDecoration.None
}

/**
 * Miroir de `.ayah-number` (style.css:539-552) - contenu d'un
 * `InlineTextContent` (voir `QuranAyahFlow`), taille EXACTEMENT egale au
 * `Placeholder` qui lui reserve sa place dans le texte (`badgeSizeSp`,
 * derive de 28.dp) - pas de marge propre ici, l'espacement texte<->bulle
 * vient des espaces litteraux ajoutes autour de `appendInlineContent` dans
 * l'`AnnotatedString` (miroir de `margin: 0 4px` sur `.ayah-number`).
 */
@Composable
private fun AyahNumberBadge(numberLabel: String) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .border(1.dp, TilawaColors.MushafAccent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(numberLabel, style = TilawaType.LabelSm.copy(fontSize = 12.sp), color = TilawaColors.MushafAccent)
    }
}

@Composable
private fun LatencyBadge(computeLatencyMs: Double?, displayLatencyMs: Double?, backlogChunks: Int) {
    val parts = buildList {
        if (computeLatencyMs != null) add("calcul ${round(computeLatencyMs).toInt()}ms")
        if (displayLatencyMs != null) add("son->affichage ${round(displayLatencyMs).toInt()}ms")
        if (backlogChunks > 0) add("retard $backlogChunks chunk${if (backlogChunks > 1) "s" else ""}")
    }
    Text(parts.joinToString("  ·  "), style = TilawaType.LabelSm, color = TilawaColors.OutlineVariant)
}

/**
 * Port du "Bottom Audio / Interaction Controller" (revealed_mode/code.html) -
 * ordre DOM reel : [tune, history] | MIC | [visibility(peek), palette-remplace-ici-par-rien].
 * Le bouton "palette" du mockup n'a pas de comportement defini cote Dani_app
 * (pas de reglage d'apparence par page) - omis plutot que cable a un no-op
 * silencieux ; "visibility" reproduit le vrai bouton "peek" de Dani_app
 * (`peekToggleEl`/`peekMode`, record.js), pas une simple decoration.
 */
@Composable
private fun MushafBottomController(
    isListening: Boolean,
    isFinalizing: Boolean,
    isLoadingModels: Boolean,
    peekMode: Boolean,
    trust: Double?,
    onMicClick: () -> Unit,
    onPeekToggle: () -> Unit,
    onTuneClick: () -> Unit,
    onHistoryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(TilawaShapes.BottomNavTop)
            .background(TilawaColors.SurfaceContainer.copy(alpha = 0.92f))
            .border(1.dp, TilawaColors.Primary.copy(alpha = 0.15f), TilawaShapes.BottomNavTop),
    ) {
        // `#trust-meter` (index.html:73-77) : premier enfant de `footer.bottom-bar`,
        // affiche UNIQUEMENT pendant l'ecoute (`trustMeterEl.hidden = !recording`).
        if (isListening && trust != null) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                TrustMeter(trust)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // EQUAL WEIGHT on both side groups, not SpaceBetween. The left group
            // holds two icons and the right holds one, so SpaceBetween centred the
            // free space rather than the button and the mic sat off to one side.
            // Matching weights make the mic the exact middle of the bar whatever
            // each side contains.
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControllerIcon(AppIcons.Tune, onClick = onTuneClick)
                ControllerIcon(AppIcons.History, onClick = onHistoryClick)
            }
            MicButton(isListening = isListening, isFinalizing = isFinalizing, isLoadingModels = isLoadingModels, onClick = onMicClick)
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControllerIcon(if (peekMode) AppIcons.Visibility else AppIcons.VisibilityOff, onClick = onPeekToggle, active = peekMode)
            }
        }
        // "Terminer la session" en cours (voir LiveCorrectionViewModel.endSession) -
        // vidage du backlog + flush() de l'encodeur, peut prendre un moment sur
        // un backlog important (voir app/README.md) : feedback explicite plutot
        // qu'un bouton qui semble juste ignorer le tap.
        if (isFinalizing) {
            Text(
                "إنهاء الجلسة...",
                style = TilawaType.LabelSm,
                color = TilawaColors.OnSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun ControllerIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, active: Boolean = false) {
    IconButton(onClick = onClick, modifier = Modifier.size(TilawaSpacing.TouchTarget)) {
        Icon(icon, contentDescription = null, tint = if (active) TilawaColors.Primary else TilawaColors.Secondary)
    }
}

@Composable
private fun MicButton(isListening: Boolean, isFinalizing: Boolean, isLoadingModels: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "mic-pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse-scale",
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse-alpha",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
        if (isListening) {
            Box(Modifier.size(64.dp * pulseScale).clip(CircleShape).background(TilawaColors.Primary.copy(alpha = pulseAlpha)))
        }
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (isFinalizing) TilawaColors.Primary.copy(alpha = 0.5f) else TilawaColors.Primary),
            contentAlignment = Alignment.Center,
        ) {
            if (isFinalizing || isLoadingModels) {
                // Also while LOADING. The first press has to copy a ~70MB model out of
                // assets and build the ONNX session, which takes seconds: the button
                // went square with nothing visibly happening, which is
                // indistinguishable from the button being broken.
                CircularProgressIndicator(color = TilawaColors.OnPrimary, modifier = Modifier.size(24.dp))
            } else {
                IconButton(onClick = onClick) {
                    // Square (stop) while recording, mic when idle - so the button
                    // shows what pressing it will DO, not what mode you are in.
                    Icon(
                        if (isListening) AppIcons.Stop else AppIcons.Mic,
                        contentDescription = if (isListening) "Arreter" else "Microphone",
                        tint = TilawaColors.OnPrimary,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121414)
@Composable
private fun MushafScreenPreview() {
    TilawaWarshTheme {
        MushafScreen(
            state = PreviewMushafState,
            onMicClick = {},
            onPeekToggle = {},
            onTuneClick = {},
            onHistoryClick = {},
            onBrowseClick = {},
            onPreviousPage = {},
            onNextPage = {},
        )
    }
}
