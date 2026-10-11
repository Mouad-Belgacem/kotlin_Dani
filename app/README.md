# app

Application Android - assemblage seul, peu de logique propre. Depend de tous
les modules `core-*` et les cadence via `core-pipeline`.

**Ecran principal : `ui/mushaf/MushafScreen.kt` (page de mushaf reelle,
reveal progressif) - remplace l'ancien `ui/livecorrection/LiveCorrectionScreen`
(carte compacte, mockup `live_correction`, SUPPRIME) suite a une decision
explicite de suivre le VRAI Dani_app (captures d'ecran reelles + code source
`Dani_app/web/app.js`/`record.js`) plutot que ce mockup Stitch, qui divergeait
de Dani_app sur plusieurs points (theme, mise en page, mapping de couleur -
voir plus bas).**

**Etat de validation : rendu statique ET micro reel (bouclage acoustique)
verifies sur device (Note10) apres reconnexion USB.** Rendu statique
conforme (theme sombre/dore, pastille sourate/juz', cadre de sourate,
bulles de verset, couleurs neutres pour le texte correct - plus de vert,
soulignement ambre/rouge pour duree/erreur, texte masque bien invisible
mais garde son emprise de mise en page). Micro reel : la pastille suit
reellement le recitant en direct (`سورة يوسف | الجزء الثالث عشر` observe
pendant la lecture de `Dani_app/audio.wav`), la page se tourne quand le
curseur change de page, les bulles de verset sont a la bonne position,
`SessionSummaryScreen` affiche des compteurs bruts coherents (56/0/0 sur
cette capture) apres arret de l'ecoute.

**Bug reel trouve et corrige PAR cette verification** (pas en relisant le
code) : `PipelineComponents.processChunk()` utilisait
`realSession?.processChunk(...) ?: experimentalSession!!.processChunk(...)` -
`?:` ne distingue pas "realSession est null" de "realSession.processChunk()
a legitimement retourne null" (le cas le PLUS frequent), et tombait sur
`experimentalSession!!` qui plante des que ce champ est reellement null
(mode production). **Crash confirme au premier chunk sans resultat** - le
chemin production n'avait jamais ete reellement rexecute depuis l'ajout du
mode experimental (`USE_EXPERIMENTAL_CTC_ONLY_PIPELINE`) qui a introduit
cette ligne. Fixe en testant explicitement quelle session est active.

**Observation, pas un bug** : `Channel.UNLIMITED` (decouplage
capture/traitement, voir plus bas) signifie qu'apres avoir arrete l'ecoute,
le traitement continue de vider le backlog deja capture (observe : 650+
chunks encore en attente a l'arret, sur une capture de ~105s a ~3-4x plus
lent que le temps reel) avant de s'arreter reellement - comportement voulu
(aucun chunk jete), mais peut prendre plusieurs minutes de traitement
apres coup si l'ecoute a dure longtemps a la vitesse de calcul actuelle.

**Trois bugs REELS supplementaires trouves sur device (screenshot utilisateur
+ tests cibles), tous corriges** :

1. **En-tete/basmala manquants pour la 2e sourate d'une page.**
   `buildPageContent()` (ViewModel) ne calculait `suraHeader`/`basmala` que
   pour `segments.first()` - une page portant la fin d'une sourate courte ET
   le debut d'une autre (ex: Al-Layl puis Ad-Duha, observe sur capture
   utilisateur) n'affichait donc PAS de cadre/basmala pour la seconde. Fixe
   en testant `seg.from == 1` pour CHAQUE segment de la page (comme
   `buildPageContent()`, app.js) - `MushafPageContent.suraHeader`/`.basmala`
   (un seul chacun) remplaces par `MushafPageContent.items: List<MushafPageItem>`
   (SuraHeader/Basmala/Ayah, dans l'ordre DOM exact, un item par occurrence
   reelle). **Reverifie sur device** : `سورة الشمس` PUIS `سورة اللَّيل`
   affiches correctement, dans l'ordre, sur la meme page.
2. **Numeros de verset colles/superposes au texte.** `.ayah-number`
   (style.css:545) a `margin: 0 4px` - la bulle Compose (`InlineTextContent`)
   n'avait AUCUNE marge. Deux correctifs successifs sur cette meme bulle,
   AUCUN suffisant seul :
   a. elargir le `Placeholder`/ajouter un espace des deux cotes (28dp ->
      38sp, puis 50sp) : a **deplace** le probleme d'un verset a l'autre au
      lieu de l'eliminer (verifie sur Ar-Rahman : l'ayah 2 se corrigeait,
      l'ayah 51 se cassait a la place, a une largeur de bulle plus grande) -
      `TextAlign.Justify` peut COMPRIMER un `InlineTextContent` sous sa
      largeur declaree quand une ligne est presque pleine, un comportement
      que Compose ne protege pas nativement (contrairement a `inline-flex`
      en CSS, cote navigateur reel).
   b. **fix structurel retenu** : `QuranAyahParagraph` (un `Text`/
      `AnnotatedString` avec `InlineTextContent`) remplacee par
      `QuranAyahFlow` (`FlowRow`, vrais noeuds de mise en page - un `Text`
      par MOT via `splitIntoWords()`, un `AyahNumberBadge` normal avec
      `Modifier.padding` par verset) : plus de risque de compression, chaque
      enfant garde sa taille mesuree. **Ecart DELIBERE et assume vs le vrai
      CSS Dani_app** (`text-align: justify`, style.css:459, confirme reel -
      pas un artefact du mockup) : le texte n'est plus etire pour remplir
      exactement chaque ligne (`FlowRow` n'a pas d'equivalent justify) -
      regression de fidelite visuelle mineure, preferee a un chevauchement
      de bulle sur le texte (bien pire, explicitement signale par
      l'utilisateur comme "une mauvaise image"). **Reverifie sur device sur
      3 pages contrastees** : Ar-Rahman (versets courts repetitifs, ou le
      bug se reproduisait), An-Naba (densite extreme, 1-2 mots/verset,
      pire cas plausible), Al-Baqara (versets longs) - aucun chevauchement
      observe sur aucune des trois, y compris aux memes positions ou le bug
      se reproduisait avant.
3. **La page suivait des suppositions du corrector PAS ENCORE etablies.**
   `goToPage()` (record.js:602-608) est place APRES le retour anticipe sur
   `judgedLength === 0` (record.js:497-500) - tant que le corrector n'a pas
   "settled", ses positions devinees peuvent "swing wildly" (commentaire
   source explicite) sans que la page doive suivre. Ce port suivait la page
   sur CHAQUE resultat, y compris ces suppositions : **observe en direct**,
   une recitation de la Fatiha faisait sauter la page vers la sourate 35
   PUIS la sourate 93 en quelques secondes, avant tout texte juge. Fixe en
   utilisant le retour (desormais utilise) de `RevealState.applyResult()`
   pour ne suivre la page QUE quand quelque chose a reellement ete juge -
   **reverifie sur device** : plus de saut premature (page reste vide tant
   que rien n'est etabli, au lieu d'afficher une sourate au hasard).

## Pagination/reveal reels (`com.tarteelwarsh.tilawa.mushaf`, PAS de l'UI)

Port fidele de la logique de Dani_app - PAS reinvente a partir des captures
d'ecran ni d'un resume : chaque classe pointe la fonction JS source qu'elle
porte.

- **`PageIndex`** : pagination officielle du Mushaf de Medine (604 pages,
  riwaya Warsh) - `app/src/main/assets/mushaf_pages.json` est une copie
  VERBATIM de `Dani_app/web/data/pages.json` (genere par
  `Dani_app/scripts/build_pages_from_kfgqpc.py` a partir du jeu de donnees
  officiel KFGQPC), pas regeneree. Numerotation (sura, aya) verifiee
  IDENTIQUE a celle de `core-logic.Finder` malgre deux sources de donnees
  Dani_app independantes (`Data_for_quran_search/` pour Finder,
  `web/data/quran.json` pour les pages) : 6214 versets des deux cotes, texte
  caractere-pres identique sur 7 echantillons (1:1, 2:1, 2:255, 9:1, 9:129,
  114:6, 18:10) - verifie directement en lisant `aya_boundaries_uthmani.bin`
  en Python, pas suppose.
- **`JuzIndex`** : port de `buildJuzIndex`/`juzAtOffset` (app.js) -
  `juz_starts.json` copie verbatim de `Dani_app/web/data/juz-starts.json`.
- **`BasmalaIndex`** : port de `buildBasmalaIndex`/`isBasmalaOffset` (app.js) -
  la basmala d'ouverture est derivee du texte lui-meme (plus long prefixe
  commun entre l'ayah 1 de la sourate 1 et celle de la sourate 2), pas une
  chaine figee : couvre generiquement les 113 sourates concernees, y compris
  le cas explicitement demande (sourate 9, At-Tawbah, sans basmala).
- **`RevealState`** : port fidele du moteur de masquage/reveal de
  `record.js` (`letterColors`/`revealedUpTo`/`bypassRanges`/
  `activeRunRange`/`renderResult()`, lignes 100-163 et 425-609) - chaque
  regle du fichier Kotlin pointe la ligne JS source. Deux bugs de portage
  trouves et corriges EN RELISANT le vrai source (pas en devinant) :
  `rangesOverlap` compare avec `<=` inclusif des deux cotes (deux plages
  simplement adjacentes comptent comme "en chevauchement"), et `clearRange`
  efface INCONDITIONNELLEMENT (pas seulement les caracteres provisoires) une
  supposition abandonnee.
- **`MushafPaint`** : port de `paintRange()` (record.js:350-392) - regroupe
  les caracteres consecutifs de meme etat visuel en un seul "run" (evite un
  span Compose par caractere, qui casserait les ligatures arabes - meme
  raison que le commentaire source, iPhone 13 mini).

**Ecart deliberement DOCUMENTE vs la description donnee en amont de ce
portage** ("le texte a venir completement absent, la mise en page se
redessine a mesure que le curseur avance") : le VRAI CSS de Dani_app
(`style.css::.unrevealed`, commentaire source explicite : "fully hidden
rather than blurred..., but keeps its layout space so text doesn't jump
around as it's revealed") garde l'espace de mise en page RESERVE
(`visibility: hidden`), il ne retire pas le texte. Suivi ici a la lettre
(couleur transparente, meme empreinte de layout) conformement a la consigne
explicite de preferer le code source reel a toute description anterieure en
cas de divergence entre les deux.

**Deuxieme ecart trouve et corrige, distinct du precedent** : le mapping de
couleur herite de l'ancien `ColorMapping.kt` (extrait du mockup
`live_correction/code.html`) peignait le texte "correct" (code 1) en VERT -
mais le VRAI CSS de Dani_app (`style.css::.state-1 { color: inherit }`) ne
distingue PAS visuellement le texte correct du texte neutre, seules les
deviations (erreur/duree) sont teintees. Nouveau mapping specifique
(`ui/mushaf/MushafColorMapping.kt`) qui suit le vrai CSS : 1/2 -> couleur
neutre (`OnBackground`), -1 -> `Error` souligne, 0 -> `FeedbackDuration`
souligne (roles semantiques portes par les tokens Material deja actes, pas
une deuxieme palette parallele important les valeurs hex de `style.css`).

## Ecran mushaf (`ui/mushaf/MushafScreen.kt`)

Style visuel : port de `stitch_tarteel_warsh_ai_teacher/revealed_mode`
(pastille "glass-card", page mushaf, bulles de verset dorees) - ce mockup-la,
contrairement a `live_correction`, correspond au vrai theme sombre/dore de
Dani_app (verifie : les deux `code.html` declarent `class="dark"` avec les
MEMES valeurs hex que `TilawaColors`/DESIGN.md). La bulle de numero de
verset (`AyahNumberBadge`) suit le VRAI `.ayah-number` de `style.css`
(cercle simple, bordure doree 1px) plutot que l'anneau pointille du mockup
Stitch (`.ayah-marker::before`, embellissement absent du vrai Dani_app).

**Contenu de la pastille sourate/juz' - ecart deliberement choisi contre le
mockup Stitch** : `revealed_mode/code.html` affiche "مكية • ٧ آيات"
(mecquois/nombre de versets) ; le VRAI Dani_app (`app.js::renderPage`,
`infoSurahEl`/`infoJuzEl`, confirme sur capture d'ecran reelle) affiche
sourate + juz'. Suit le vrai Dani_app ici, pas le mockup.

**Bouton "peek" (`onPeekToggle`, icone oeil)** : reproduit le vrai bouton
`peekToggleEl`/`peekMode` de Dani_app (montre le texte masque sans le
marquer comme recite), pas une decoration. Le bouton "palette" du mockup
n'a pas de comportement defini cote Dani_app - omis plutot que cable a un
no-op silencieux ; les icones restantes (`tune`/`history`) naviguent vers
Settings/SuraSelection.

## Ecrans secondaires (sura_selection, settings, session_summary)

Trois decisions produit deja actees, appliquees ici (le mockup Stitch les
montre "actives", ce qui serait trompeur dans cette app) :
- **`ui/settings/SettingsScreen.kt`** : selecteur de riwaya GRISE sauf Warsh
  (seule riwaya reellement supportee par tout le pipeline - segmenteur, ASR,
  Corrector, Finder) ; PAS de curseur de sensibilite du microphone
  (`core-audio.AudioCapture` n'expose aucun gain/seuil reglable, un curseur
  n'aurait rien a controler). Taille du texte/theme clair-fonce/"تلاوة
  الخلفية" : visuels uniquement pour cette passe, aucun encore cable a un
  effet reel (documente en KDoc de fichier, pas une omission silencieuse).
- **`ui/sessionsummary/SessionSummaryScreen.kt`** : legende avec les
  COMPTEURS BRUTS uniquement (`RevealState.counts()`, caracteres juges par
  code de couleur) - retire par rapport au mockup Stitch : le donut de
  precision + etiquette qualitative ("85% - ممتاز", exigerait un seuil de
  reussite inexistant cote Corrector), la ventilation par regle de tajwid
  ("غنة ناقصة"..., `Corrector` ne categorise pas ses erreurs par regle), et
  la carte de recommandation de lecon (aucun mapping lecon<->erreur dans ce
  projet). Compte des CARACTERES, pas des versets (le mockup affiche "آية") -
  agreger par verset inventerait un seuil de reussite qui n'existe nulle
  part cote Dani_app.
- **`ui/suraselection/SuraSelectionScreen.kt`** : liste elle-meme absente de
  Dani_app (sidebar minimaliste, numero+nom seuls) - suit le mockup Stitch
  pour la mise en forme. `مكية`/nombre de versets (`SuraMeta`,
  `sura_meccan.json`) est une metadonnee decorative absente de Dani_app
  (classification standard largement admise, PAS une donnee source
  Dani_app - voir KDoc de `SuraMeta.kt`). Onglets تلاوة/مراجعة affiches mais
  seul مراجعة a un effet observable pour cette passe (voir "Reste a faire").

## Micro reel (`LiveCorrectionViewModel.kt`)

`core-audio.AudioCapture` -> `core-pipeline.PipelineSession.processChunk()`
-> `RevealState.applyResult()` -> `MushafUiState`. Modeles ONNX (Zipformer +
segmenter, ~550 Mo) embarques dans les assets de PRODUCTION
(`src/main/assets/model/`, jamais commites), materialises vers le stockage
prive persistant au premier lancer (pas le cache, contrairement aux tests -
l'app reelle ne doit pas re-copier 550 Mo a chaque redemarrage). Noms de
sourate (`sura_names.json`) extraits mecaniquement de
`Dani_app/scripts/data/warshData_v10.json` (114 entrees, jamais retapees a
la main).

## Mode (وضع التلاوة/وضع المراجعة) et cycle de vie de session

Semantique VERIFIEE dans le vrai code Dani_app (pas supposee) - deux
mecanismes DISTINCTS, tous les deux portes ici :

- **Backend** (`api/main.py:36-40`) : le `mode` envoye a l'ouverture du
  websocket determine `offset` (1 pour "talawa", 2 pour tout le reste,
  y compris "muraja3a") passe a `Session.__init__` -> `Corrector(...,
  offset=offset)` (largeur de recherche du Corrector, `w = ((10*exp(-score)+offset)*width)`)
  ET `self.help_tilawa = (offset == 2)` (declenche `add_harakat` sur le
  texte ASR avant correction). **Deja porte cote Kotlin depuis un tour
  anterieur** (`PipelineSession.create(offset=...)`, `helpTilawa = (offset
  == 2)`, `AddHarakat.kt`) - jamais reellement EXERCISE jusqu'ici, `offset`
  etait toujours 1 par defaut, jamais branche a un choix utilisateur.
- **Frontend** (`record.js:63-82,591`) : `recitationMode` ("talawa"/
  "muraja3a", stocke separement en `localStorage`) attenue l'affichage des
  erreurs de PRONONCIATION individuelles en muraja3a (`color = recitationMode
  === "muraja3a" ? 1 : result.colors[i]`) - un saut de verset "bypasse"
  reste rouge dans les DEUX modes (fait structurel, pas un jugement de
  prononciation). Deja porte (`RevealState.recitationMode`), jamais branche
  non plus jusqu'ici.

**Les deux sont maintenant reellement cables**, choisis via les onglets
`SuraSelectionScreen` (`mode`/`onModeChange`, plus un etat local decoratif),
pris en compte au PROCHAIN demarrage de session
(`LiveCorrectionViewModel.setRecitationMode` - sans effet pendant une
session en cours, comme Dani_app qui n'envoie le mode qu'une fois, jamais
ensuite).

**Point de depart de session egalement porte** : Dani_app demarre chaque
session websocket a `start.sura`/`start.aya` - la ou le frontend a navigue
AVANT d'appuyer sur enregistrer (record.js:711), pas toujours 1:1. Ce port
faisait l'inverse (toujours sura=1/aya=1, la navigation "browse" etait
cosmetique) - corrige : `jumpToSura` (sura_selection) fixe desormais le
point de depart de la PROCHAINE session.

**Bug reel trouve en tracant cette logique** (pas en relisant le code
existant) : `RevealState` n'etait JAMAIS reinitialise entre deux sessions -
demarrer/arreter/redemarrer l'ecoute accumulait le reveal indefiniment,
contrairement a Dani_app (`record.js::startRecording` - "each take is a
fresh attempt, not a continuation of a previous take's judgments"). Fixe :
`PipelineModels.startNewSession()` reconstruit Corrector + `PipelineSession`/
`ExperimentalCtcOnlyPipelineSession` (avec le mode/point de depart courants)
ET une instance FRAICHE de `RevealState`, a CHAQUE `startListening()`.

**Controle d'arret de session** (`LiveCorrectionViewModel.endSession()`,
PAS un concept Dani_app direct - son architecture "logits ring" n'a pas
cette notion, necessaire specifiquement pour l'encodeur streaming Zipformer
de ce port) :
1. arrete la capture (`audioCapture.stop()`) puis **attend que le
   `captureJob` se termine NATURELLEMENT** (`join()`, jamais `cancel()`) -
   le canal `Channel.UNLIMITED` doit finir de vider tout l'audio DEJA
   capture avant de considerer la session close, sinon les derniers mots
   recites juste avant l'arret seraient perdus.
2. `finalizeSession()` (nouveau sur `PipelineSession`/
   `ExperimentalCtcOnlyPipelineSession`) : `encoder.flush()` (complete le
   dernier chunk Zipformer incomplet, deja prouve exact par
   `StreamingEncoderFlushGoldenDeviceTest`) puis traite ce qui reste comme
   une coupure DEFINITIVE (`wasl=false`) via le meme chemin de correction
   que `processChunk()`.
3. `isFinalizing` (nouveau champ `MushafUiState`) desactive le bouton micro
   et affiche "إنهاء الجلسة..." le temps du drain+flush - sans ça, un
   second tap pendant la finalisation relancerait une 2e finalisation en
   parallele.
4. navigation vers `SessionSummaryScreen` APRES, pas avant - le resume
   affiche des compteurs a jour, pas perimes.

**Verification sur device** : le flux mecanique (arret -> drain -> flush ->
finalize -> resume) confirme fonctionner sans crash ni blocage, y compris
dans le cas degenere (rien de juge cette session, resume a 0/0/0 -
`finalizeSession()` retourne `null` proprement). **Verification acoustique
COMPLETE (contenu reellement transcrit) PAS obtenue cette passe** : lecture
audio confirmee (log "lecture demarree"/duree du fichier corrects, volume
media raisonnable ~47%), mais aucun symbole CTC non-blank detecte sur
100+ secondes - contraste net avec des tests anterieurs reussis dans la
meme session. Rien trouve cote code qui expliquerait une regression ;
suspecter une cause environnementale/materielle (position du telephone,
ligne haut-parleur -> micro) plutot qu'un bug de ce tour de travail - a
reverifier.

**Bug reel trouve dans l'outillage de test (pas l'app) au passage** :
`DebugAudioPlaybackReceiver` PLANTE (`FileNotFoundException: EACCES`) si le
fichier audio pousse est a un chemin `/sdcard/` general (stockage scoped
Android 11+, refuse l'acces direct depuis une autre app que celle qui l'a
pousse) - fonctionne seulement depuis le repertoire externe propre a l'app
(`/sdcard/Android/data/com.tarteelwarsh.tilawa/files/`). Outil de test
uniquement (deja marque "a retirer avant production"), mais un crash n'est
jamais anodin - documente ici pour la prochaine fois.

## Latence son -> affichage : MESUREE, pas supposee

Instrumentation reelle (`MushafUiState.capturedAtNanos`/`resultId`,
`LiveCorrectionViewModel`, `LaunchedEffect`+`withFrameNanos` cote
`MushafScreen`) :
- **T0** = instant ou `AudioCapture` livre le dernier chunk ayant produit ce
  resultat (pas l'entree materielle reelle dans le micro - `AudioRecord`
  n'expose pas ce timestamp ici, limite assumee).
- **T1** = instant ou `PipelineSession.processChunk()` retourne (latence de
  calcul pure).
- **T2** = prochain callback de frame Choreographer apres mise a jour de
  l'etat (le signal le plus proche de "cette frame va etre presentee"
  accessible depuis Compose sans instrumentation materielle externe -
  mesure jusqu'a la planification de la frame, pas jusqu'a la lumiere
  reellement emise par l'ecran).

**Test reel effectue** (pas un test synthetique) : `DebugAudioPlaybackReceiver`
(outil de test uniquement, a retirer avant tout build de production reelle)
joue `Dani_app/audio.wav` via le VRAI haut-parleur du Note10 pendant que
l'app ecoute via son VRAI microphone (bouclage acoustique complet - trouve
et corrige au passage un bug reel de coordonnees de tap dans le harnais de
test, l'ancien calcul d'echelle etait faux de ~170px, confirme par
`uiautomator dump` plutot que reajuste a l'oeil).

**Mesure initiale (15 resultats reels, avant toute correction)** :

| | min | moyenne | max |
|---|---|---|---|
| calcul (T1-T0) | 1975ms | **2654ms** | 3474ms |
| son -> affichage (T2-T0) | 2039ms | **2681ms** | 3500ms |

L'ecart T2-T1 (temps d'affichage pur) etait de 16-65ms (moyenne ~26ms) - le
rendu Compose n'a jamais ete le goulot. Deux problemes distincts identifies
et corriges depuis, dans cet ordre :

### 1. Decouplage capture/traitement (inconditionnel, corrige en premier)

`AudioCapture.start()` emettait dans un `Flow` froid que `processChunk()`
consommait de facon SYNCHRONE (`collect { ... }` bloquait le meme coroutine
que la boucle `AudioRecord.read()`) - pendant chaque appel de calcul, la
capture micro elle-meme etait a l'arret, pas seulement l'affichage : sur un
enregistrement plus long, ceci menait mecaniquement a des echantillons
perdus (debordement du buffer interne `AudioRecord`), pas seulement a un
retour visuel en retard.

**Correction** : un `Channel<TimestampedChunk>(Channel.UNLIMITED)` separe
desormais un coroutine PRODUCTEUR (`AudioCapture.collect` -> `channel.trySend`,
jamais suspendu) d'un coroutine CONSOMMATEUR (`for (chunk in channel) { ...
processChunk... }`). Volontairement NON borne plutot qu'un drop des chunks
en trop : `core-audio` garantit un flux gapless et strictement ordonne, et
`core-asr` EN DEPEND (etat Fbank/CTC streaming) - sauter un chunk
corromprait silencieusement la transcription (frames non contigues que le
modele n'a jamais vues a l'entrainement). `MushafUiState.backlogChunks`
expose le retard accumule (chunks captures mais pas encore traites) -
visible dans l'UI, pas cache.

### 2. Decomposition chronometree reelle par etage (pas une attribution par raisonnement)

`ChunkStageTiming` (core-pipeline) + `EncoderStageTiming` (core-asr) +
`SegmenterStageTiming` (core-segmenter) : parametres optionnels ajoutes aux
signatures existantes (`pushAudio()`, `segment()`, `processChunk()`) -
retrocompatibles, tous les tests dores deja verts (`StreamingEncoderGoldenDeviceTest`,
`SegmenterGoldenDeviceTest`, `PipelineSessionGoldenDeviceTest`) le restent
sans modification. Mesure REELLE sur 50 chunks reels (Note10, apres le
decouplage ci-dessus) :

| etage | % du temps total |
|---|---|
| **segmenteur (inference ONNX)** | **88.4%** |
| Fbank (Zipformer + segmenteur combines) | 6.2% |
| ASR (inference ONNX Zipformer) | 5.4% |
| Corrector | 0.0%* |

\* 0 mesure reelle sur cette fenetre : aucun segment de parole "assez long"
n'a ete confirme pendant ces 50 premiers chunks (le `Corrector` n'est
atteint que dans cette branche) - pas encore de chiffre reel pour ce cas,
signale explicitement plutot que suppose negligeable par defaut.

Le segmenteur domine sans ambiguite, et croit avec la duree du buffer -
mesure, pas suppose : 146ms a ~0,2s de buffer -> 863ms a ~5,1s (`core-segmenter`
rescanne l'INTEGRALITE du buffer roulant a chaque appel, comportement voulu,
copie de `Dani_app/api/pipeline.py::segment_audio`).

### 3. Fenetre bornee pour le segmenteur (corrige la croissance non bornee, pas juste le symptome)

**Ecart DELIBERE vs Dani_app** (voir `PipelineConfig.SEGMENTER_WINDOW_SECONDS`,
`PipelineSession.kt`) : le segmenteur ne recoit plus l'integralite du buffer
roulant (jusqu'a 30s) mais seulement ses dernieres secondes (**10s** dans
cette premiere version, **reduit a 4s ensuite** - voir section 4). Les
positions qu'il retourne sont relatives a cette fenetre et reoffsetees avant
tout usage en coordonnees du buffer complet. `scripts/generate_pipeline_goldens.py`
reproduit EXACTEMENT le meme fenetrage (meme discipline que partout ailleurs
dans ce portage) - le golden a ete regenere et `PipelineSessionGoldenDeviceTest`
reste vert (egalite stricte) a chaque changement.

**Contrepartie de correction ACCEPTEE, pas cachee** : `intervals[0]` (le
plus ancien segment de parole encore dans le buffer) peut avoir commence
avant cette fenetre pour un verset recite plus lentement qu'elle - son debut
reel ne sera alors jamais vu par le segmenteur, contrairement au
comportement Dani_app (buffer entier, jamais fenetre).

**Validation reelle avec la fenetre a 10s** (bouclage acoustique, 119 chunks) :
avant que la fenetre se remplisse (buffer < 10s), `segModel` continue de
croitre normalement (86-1247ms) ; **une fois pleine (buffer >= 10s, n=20) :
PLATEAU a 1017-1460ms (moyenne 1226ms)** au lieu de continuer a croitre sans
borne - coherent avec l'extrapolation de la tendance pre-correction
(~146ms/s de buffer, projetee a 10s : ~1,5s, contre ~4,5s projetes a 30s).
Decouplage confirme fonctionnel sur cette duree aussi : `backlog` croit de 1
a 662 chunks sans jamais stagner ni faire perdre d'echantillons.

### 4. Fenetre reduite a 4s + throttle de l'appel segmenteur a 200ms

Sur decision explicite (le plateau a 10s restait tres au-dessus du budget
100ms/chunk) : la fenetre est reduite de 10s a **4s**, et l'appel au
segmenteur lui-meme (pas le flux audio/ASR, toujours nourri a chaque chunk
sans exception) est desormais **throttle a 200ms** - deux ecarts
complementaires, pas un remplacement l'un de l'autre (voir
`PipelineConfig.SEGMENTER_WINDOW_SECONDS`/`SEGMENTER_CALL_INTERVAL_SAMPLES`).
Meme discipline : `scripts/generate_pipeline_goldens.py` reproduit
EXACTEMENT les deux, golden regenere, **`PipelineSessionGoldenDeviceTest`
reste vert en egalite stricte** (30 resultats sur les memes 15s d'audio,
contre 52 avec la version precedente - la reduction du nombre de resultats
est elle-meme un signe que le throttle change reellement le comportement
observable, pas un no-op).

**Validation reelle** (bouclage acoustique, 157 chunks) :
- **Throttle confirme** : 78/157 chunks etaient de VRAIS appels segmenteur
  (`segModel>0`) - quasi exactement 50%, comme attendu d'un intervalle de
  200ms applique a des chunks de 100ms. Les chunks throttles ne coutent
  quasiment rien (`total≈1,2ms`, juste le Fbank de l'ASR).
- **Plateau confirme, et net toujours plus bas** : avant que la fenetre de
  4s se remplisse, `segModel` moyenne 407ms (n=19 vrais appels) ; une fois
  pleine, moyenne 589ms (n=59) - **plateau grossierement moitie moins cher
  que le plateau a 10s (1226ms)**, coherent avec une fenetre 2,5x plus
  courte. Le plateau est stable dans la duree observee (moyenne 624ms sur
  la 1ere moitie de ces 59 mesures, 555ms sur la 2e - stable/legerement en
  baisse, pas de derive croissante cette fois).
- **Effet combine** : rapport "retard accumule / audio reel ecoule"
  (`(backlog + n) / n`, une mesure directe de combien de fois plus lent que
  le temps reel le systeme tourne) - **~6,6x avec la fenetre a 10s seule
  (119 chunks, backlog 1->662) contre ~3,2x avec fenetre 4s + throttle 200ms
  (157 chunks, backlog 0->344)** - amelioration reelle d'un facteur ~2 en
  plus du fenetrage seul, mesuree, pas estimee.
- Repartition du temps total (tous chunks, throttles inclus) : segmenteur
  85,9%, ASR 7,2%, Fbank 6,4%, **Corrector 0,5%** (premiere mesure REELLE
  non-nulle du Corrector sur ce type de test - 233ms cumules sur au moins
  un segment confirme "assez long" pendant la capture).

**Etat actuel** : toujours pas de temps reel strict (100ms/chunk) - le
systeme tourne encore ~3,2x plus lentement que le flux audio reel, mais
c'est une amelioration mesuree (~2x) par rapport a la seule fenetre a 10s,
et tres loin du ~25-35x du tout debut (avant tout correctif). Pistes NON
tentees pour aller plus loin (a discuter) : modele segmenteur
allege/quantifie, execution provider accelere (NNAPI/XNNPACK), fenetre
encore plus courte (compromis cout/detection d'enonces longs a
rediscuter), throttle plus agressif (400ms+).

### 5. Experience sans segmenteur (`ExperimentalCtcOnlyPipelineSession`) - PAS un chemin de production

Sur demande explicite : retirer TEMPORAIREMENT `core-segmenter` du pipeline
(88-86% du cout mesure ci-dessus) pour isoler ce que coute le Zipformer
seul, et verifier si sa disparition suffit a repasser sous 100ms/chunk par
accident, ou si un autre goulot apparait. Voir
`core-pipeline/ExperimentalCtcOnlyPipelineSession.kt` (KDoc complet) et
`core-asr/StreamingCtcDecoder.kt#msSinceLastNonBlank()` pour le mecanisme :
le signal de fin d'enonce du segmenteur (`len(intervals)>1`) est remplace
par un PROXY - la duree ecoulee depuis le dernier symbole CTC non-blank
decode ; au-dela de `PipelineConfig.EXPERIMENTAL_CTC_SILENCE_THRESHOLD_MS`
(400ms, point de depart, pas une valeur validee), on traite ca comme une
coupure. **Ce proxy n'est PAS un remplacement silencieux et permanent du
segmenteur - un blanc CTC local reflete ce que le modele croit avoir
reconnu, pas un niveau acoustique mesure.** Bascule via
`LiveCorrectionViewModel.USE_EXPERIMENTAL_CTC_ONLY_PIPELINE` (`false` par
defaut - a remettre a `false` apres toute mesure ponctuelle).

**Validation reelle** (bouclage acoustique, capture continue 2594 chunks /
~4min20, dont ~105s de lecture reelle de `audio.wav` puis silence ambiant) :

- **Pas de goulot ASR cache confirme au regime stable** : sur le dernier
  tiers de la capture (silence ambiant essentiellement, mais qui exerce
  quand meme l'ASR a chaque chunk), `backlogChunks` moyen retombe a **0,38**
  (identique au tiers du milieu, 0,38) - coherent avec la mesure precedente
  (ASR seul, ~397 vrais appels de fenetre Zipformer sur les 2594 chunks,
  moyenne 212,5ms/appel, largement absorbe par les ~6 chunks de 100ms
  qui s'ecoulent entre deux appels).
- **MAIS un goulot different, jusqu'ici masque par le segmenteur, est
  revele : le Corrector lui-meme, une fois prive du throttle qu'il
  heritait GRATUITEMENT du segmenteur.** Dans `PipelineSession` (production),
  `corrector.correct()` est deja, de fait, appele au plus une fois tous les
  200ms - il est place APRES le `return null` du throttle d'appel
  segmenteur (`PipelineSession.kt`, throttle de la section 4 ci-dessus),
  qui le protege donc en passant. `ExperimentalCtcOnlyPipelineSession`
  n'a PAS d'equivalent : `corrector.correct()` y est appele a CHAQUE chunk
  (100ms) des que `tilawa.length >= MIN_TILAWA_CHARS`, sans aucun throttle
  propre. Mesure directe : son propre cout, sur des sequences ou `cut` ne
  se declenche pas (voir point suivant), atteint **100 a 175ms par appel,
  soutenu sur des dizaines de chunks consecutifs** - deja au-dela, a lui
  seul, du budget de 100ms/chunk, independamment de l'ASR ou du segmenteur.
  Consequence observee : un episode ou `backlogChunks` grimpe de 5 a **35**
  (3,5s de retard accumule) pendant ~15s, entierement porte par le
  Corrector (`asrModel=0.0ms` sur la plupart de ces lignes - seul
  `corrector` est non-nul).
- **Cause du "cut ne se declenche pas" : exactement le risque documente
  dans le code (`StreamingCtcDecoder.msSinceLastNonBlank()`,
  `ExperimentalCtcOnlyPipelineSession` KDoc) - un blanc CTC local n'est pas
  un silence acoustique.** Le seuil de 400ms se declenche trop rarement
  pendant de la parole continue fluide : `tilawa` grandit sans jamais etre
  finalise (`text.len` observe : 12 -> 45 -> 52 -> 107 caracteres sur la
  meme sequence, au lieu d'etre coupe en plusieurs enonces courts comme le
  ferait une vraie VAD), donc le Corrector est re-appele, chunk apres
  chunk, sur une chaine toujours plus longue. Effet secondaire observe : le
  curseur (`sura`/`aya`) est reste bloque sur **la meme sourate/verset
  (58:21) pendant toute la capture** (390/390 `PipelineResult`, dont 309
  avec un texte non-vide) - il n'a jamais avance, contrairement au
  comportement attendu avec une vraie segmentation.
- **Chiffres globaux** (2594 chunks) : `backlogChunks` moyen 1,25 (vs 0,38
  en regime stable - l'ecart est entierement du a l'episode Corrector
  ci-dessus, concentre sur le premier tiers de la capture), max 35 ;
  `compute` moyen 153ms, median 9,7ms (**distribution tres bimodale** - la
  plupart des chunks ne font quasi rien, une minorite spike a 500-3544ms) ;
  31,1% des chunks depassent le budget 100ms. Repartition du temps de
  calcul cumule : ASR 21,2%, Corrector 4,2% (moyenne trompeuse - concentre
  sur quelques centaines de chunks consecutifs, pas etale), Fbank 1,9%.

**Reponse honnete a la question posee** : retirer le segmenteur ne fait
PAS retomber le systeme sous 100ms/chunk par accident (31,1% des chunks le
depassent encore), mais PAS non plus a cause d'un goulot ASR cache - l'ASR
seul tient le budget en regime stable. Le vrai goulot revele est
architectural, pas un cout brut : le Corrector a besoin de SON PROPRE
throttle explicite, independant du segmenteur - en production, il en
recevait un gratuitement en etant place derriere le throttle d'appel
segmenteur. Cette experience conflait involontairement deux choses
("retirer le segmenteur" et "retirer le throttle implicite du Corrector")
- toute mesure future de ce type devrait re-throttler le Corrector
explicitement (ex: memes 200ms) pour isoler proprement le cout de l'ASR
seul. Non tente ici (hors perimetre de cette passe diagnostique) :
implementer ce throttle et refaire la mesure. **A ne pas confondre avec
une conclusion "le proxy CTC est pret pour la production" - il ne l'est
pas** (curseur bloque, `cut` peu fiable pendant la parole continue) :
cette section documente une experience de MESURE, pas une decision de
design pour `PipelineSession` (production, inchangee).

## Audit + corrections (session dediee) - liste presentee avant correction, puis traitee dans l'ordre confirme

Audit complet demande explicitement (hors segmenteur, laisse de cote) :
chaque bouton compare a ce qu'il fait REELLEMENT, comparaison systematique
au vrai `Dani_app/web/index.html` (pas seulement ce qui avait deja ete
discute), verification separee de la semantique وضع المراجعة/وضع التلاوة.

**Semantique وضع المراجعة/وضع التلاوة - VERIFIEE dans `process_chunk()` en
entier (api/pipeline.py:560-703), pas seulement les deux points deja
connus** : le mode revue fait tourner l'ASR/segmenteur/Corrector
IDENTIQUEMENT dans les deux modes - `corrector.cursor` avance, `uth_colors`
contient les vraies valeurs -1/0/1 en interne, AUCUNE branche conditionnelle
sur `mode` ailleurs que `offset` (largeur de recherche + `add_harakat`,
deja porte) et le filtrage d'AFFICHAGE cote frontend (deja porte,
`RevealState.recitationMode`). Confirmation, pas une correction.

**1. Navigation de page manuelle** - `#prev-page`/`#next-page`/`#page-number`
(index.html:62-70) n'avaient pas d'equivalent (seul moyen de changer de
page : reciter, ou sauter vers une autre sourate). Ajoute :
`PipelineModels.goToPage(delta, peekMode)` (miroir de `goToPage()`,
app.js:248-251, clampe silencieusement aux bornes), `PageNavRow` (`›`/`‹`,
meme ordre DOM RTL que le vrai bouton "next" - "sits where you'd naturally
reach to flip the next page in"), `MushafPageContent.pageNumberLabel`.
Rafine au passage : la position de depart d'une session suit desormais
`firstAyahOfCurrentPage()` (miroir exact de record.js:616-630, PAS un point
"browse" separement memorise comme avant cette passe) - consequence directe
d'avoir enfin une vraie navigation de page a suivre.

**2. Compteur de confiance visible** - `#trust-meter`/`updateTrustMeter()`
(index.html:73-77) affiche UNIQUEMENT pendant l'ecoute ; la donnee
(`PipelineResult.trust`, 0-6) existait deja et EST deja validee par
`CorrectorGoldenTest` contre le vrai `Correction.py` (`min(2*threshold,
score+dscore(...))` identique aux deux, verifie ligne a ligne) - le seul
gap etait l'affichage. `TrustMeter` ajoute (`MushafUiState.trust`, "gray at
0 -> yellow at 3 -> green at 6" - commentaire source exact de `trustToColor`,
record.js - roles semantiques deja actes plutot que ses valeurs RGB
litterales).

**3. ☰/🔍 relies** - `MushafTopBar` avait `onClick = {}` sur les deux ;
relies vers `sura_selection` (miroir de `sidebar-toggle`, index.html:48,
qui ouvre la sidebar contenant la recherche - meme destination pour les
deux, comme chez Dani_app).

**4. Persistance minimale de session (vraie fonctionnalite, pas juste un
libelle corrige)** - `SessionHistoryStore` (nouveau, `history/`) : date,
plage recitee (sura/aya debut-fin), compteurs bruts, confiance - JSON plat
dans le stockage prive (pas Room/SQLite, hors de proportion a cette
echelle), reutilise le `JsonParser` deja present. `endSession()` persiste
desormais un enregistrement avant de naviguer vers le resume. **L'icone
"history" de la barre du bas (point 6, ci-dessous) montre maintenant ce
VRAI historique** (`SessionHistoryScreen`, nouveau) plutot que la liste des
sourates - resolution plus coherente des points 4+6 ensemble que de
renommer une icone qui pointerait quand meme vers autre chose.

**5. Vraie bascule de theme clair/sombre** - `TilawaColors` (Color.kt)
n'etait PAS reactif au theme (122 sites d'appel, objet statique fige sur le
sombre) : refactor en `TilawaPalette` (data class) + `LocalTilawaPalette`
(CompositionLocal) + `TilawaColors.X` en proprietes `@Composable get()` -
memes 122 sites d'appel inchanges, desormais reactifs. Palette claire
ancree dans le VRAI Dani_app (`style.css:10-28`, `:root{}`, le theme PAR
DEFAUT sur desktop - aucun mockup Stitch n'en definit un, deja documente
comme "pas un oubli") pour les roles semantiques principaux
(primary/background/surface/onBackground/onSurface/error/outline) ; les
roles Material 3 que Dani_app ne couvre pas (containers, tertiary...) sont
derives algorithmiquement par `lightColorScheme()`, pas choisis a la main.
`AppearanceMode` (AUTO/DARK/LIGHT) persiste via SharedPreferences
(`LiveCorrectionViewModel.setAppearanceMode`), AUTO suit
`isSystemInDarkTheme()`.

**(a) Accueil premier lancement** - l'app demarrait sur un ecran vide
avant tout tap sur le micro (aucune indication). Deux corrections
liees : `MainActivity` charge desormais le pipeline + affiche la page 1
DES le lancement (miroir du vrai Dani_app, app.js fin de fichier :
`renderPage(1)` INCONDITIONNEL au chargement, jamais derriere un bouton -
ecart reel de ce port, corrige) ; un texte d'indice discret ("اضغط على
الميكروفون...") s'affiche des qu'une page existe et que rien n'est en
cours.

**(b) Crash EACCES de `DebugAudioPlaybackReceiver` corrige proprement** -
`FileNotFoundException` non rattrapee (stockage scoped, chemin `/sdcard/`
general) faisait planter TOUTE l'app, pas juste echouer la lecture. Fixe
des deux cotes : `path` relatif resout desormais contre
`context.getExternalFilesDir(null)` (le seul repertoire garanti accessible
sans exception scoped-storage) par defaut, ET tout le chargement est dans
un try/catch - un fichier introuvable ne doit JAMAIS faire planter l'app,
meme un outil de test (reste a retirer avant production).

**Verification sur device COMPLETE** (passe suivante, apres deverrouillage du
Note10) - les 8 corrections ci-dessus ET trois problemes signales par
l'utilisateur en test reel ont ete revus/corriges :

- Navigation de page/historique/theme clair confirmes fonctionnels a
  l'ecran (voir captures de session).
- **Curseur "حجم النص" (taille du texte) retire** - c'etait un `Slider`
  purement decoratif (`mutableFloatStateOf` local, ne pilotait aucune
  echelle de police reelle) ; l'utilisateur l'a signale sans effet et a
  demande son retrait plutot qu'une implementation complete d'echelle de
  police (`SettingsScreen.kt`).
- **Navigation de page : boutons ‹/› retires au profit du swipe** -
  miroir de la regle mobile reelle de Dani_app
  (`style.css:630-634`, `.page-nav{display:none}` sous
  `@media max-width:700px`, "Swipe covers page navigation on touch devices
  now"). `SwipeablePage` (`MushafScreen.kt`) reproduit `app.js` : memes
  seuils (`DRAG_COMMIT_FRACTION=0.28`, `DRAG_EDGE_RESISTANCE=0.35`), meme
  mapping de direction (glisser a droite = page suivante). Simplification
  assumee : pas d'apercu de la page adjacente glisse sous le doigt (le
  mecanisme web anime deux pages simultanement) - seule la page courante
  est translatee, la vraie page suivante/precedente est demandee au
  relachement si le seuil est franchi.
- **Page qui tient dans l'ecran sans defiler** - la structure DOM
  `header/.page-area(flex:1)/footer` de Dani_app (pas un defilement plein
  ecran) est reproduite : `Column` avec le contenu de page en
  `weight(1f)`, defilement interne SEULEMENT en filet de securite (miroir
  de `.page-content{overflow-y:auto}`, jamais sollicite en pratique a
  taille de police calibree - sauf pages a versets longs, ex. Al-Imran 50,
  ou le filet joue reellement, sans casser l'affichage).
- **Bug CRITIQUE resolu : texte "eparpille" en desordre pendant une
  correction active (وضع التلاوة)** - signale avec capture d'ecran par
  l'utilisateur. Root-cause confirmee sur device (pas supposee) :
  `QuranAyahFlow` utilisait un `FlowRow` mot-par-mot (introduit pour fixer
  le bug de bulles-sur-texte precedent), qui mesure et fait passer a la
  ligne CHAQUE mot comme un widget independant - contrairement au moteur
  de paragraphe reel (navigateur/`Text` Compose) qui calcule les sauts de
  ligne sur TOUT le texte, visible et masque, comme un flux continu. De
  longues suites de mots invisibles (`Unrevealed`, texte non encore
  recite) creaient de larges vides, avec pour effet qu'un court fragment
  REELLEMENT visible se retrouvait seul sur sa ligne. Fixe en revenant a
  `InlineTextContent` (un seul `Text` par groupe de versets, reflow de
  paragraphe reel) mais SANS `TextAlign.Justify` cette fois
  (`TextAlign.Right`) - `Justify` etait la cause racine du PREMIER bug
  (bulle comprimee sous sa largeur declaree pour remplir la ligne) ;
  sans lui, rien ne pousse a comprimer le `Placeholder`, eliminant le
  bug 1 sans reintroduire le bug 2. Confirme stable sur device via
  plusieurs captures pendant une correction active reelle (`سورة الطارق`,
  toute la sourate revelee au fil de l'audio, aucun chevauchement ni
  eparpillement).
- **Bug reel trouve EN CHERCHANT A REPRODUIRE le bug ci-dessus** :
  `DebugAudioPlaybackReceiver` (outil de test) arretait la lecture apres
  ~1s au lieu des ~105s reelles du fichier, sans aucune erreur loguee -
  `MediaPlayer` etait une variable locale de `onReceive()`, retenue en vie
  seulement par les closures de callback ; `onReceive()` retourne
  immediatement (lecture asynchrone), rien n'empechait le GC de collecter
  l'instance pendant que le native player jouait encore. Fixe en gardant
  une reference forte dans le companion object, liberee a la fin/erreur.
- **Libelle trompeur corrige sur `session_summary`** : le bouton affichait
  "إنهاء وحفظ الجلسة" (terminer ET enregistrer) alors que la sauvegarde
  (`SessionHistoryStore.append`) a deja eu lieu AVANT que cet ecran
  s'affiche (`endSession()` termine puis navigue) - le bouton ne fait plus,
  a ce stade, que fermer l'ecran. Renomme en "تم" (Fait), qui ne pretend
  rien de plus que ce qu'il fait reellement.
- **Palette claire : violet Material Baseline corrige** - bug reel trouve
  sur device : `lightColorScheme(primary=...)` sans `secondary`/`tertiary`
  explicites retombe sur la palette violette PAR DEFAUT de Material 3 pour
  ces roles precis (`ColorLightTokens`), utilisee par `surfaceContainer`/
  `secondaryContainer`/etc. (pastille de riwaya, carte "تلاوة الخلفية",
  pastilles de theme non selectionnees) - en rupture totale avec la
  palette chaude (creme/brun/or) du reste du theme clair. Corrige en
  choisissant ces roles a la main dans la meme famille chaude/neutre que
  `TilawaDarkPalette` specifie deja explicitement (`Color.kt`).
- **Badge de latence (diagnostic dev) desormais cache en release** -
  affiche INCONDITIONNELLEMENT jusqu'ici (texte technique "calcul 409ms ·
  son→affichage 152279ms" visible par un utilisateur final ; la valeur
  peut d'ailleurs devenir absurde si l'ecran Mushaf n'a pas ete au premier
  plan entre deux resultats, `withFrameNanos` mesurant alors un ecart bien
  plus grand que la vraie latence son->affichage). `buildFeatures.buildConfig
  = true` ajoute (`app/build.gradle.kts`) pour gater l'affichage derriere
  `BuildConfig.DEBUG` - toujours visible en debug (utile au developpement),
  jamais en release.

## Reste a faire

- Rapprocher le temps de calcul du budget 100ms/chunk (le systeme tourne
  encore ~3,2x plus lentement que le temps reel, meme apres fenetrage a 4s
  + throttle a 200ms).
- `DebugAudioPlaybackReceiver` : outil de test (crash EACCES + arret
  premature corriges, voir ci-dessus), a retirer avant production.
- `RecitationMode.TALAWA` (mode "تلاوة" strict, erreurs de prononciation
  colorees en rouge) : le type et la semantique complete sont VERIFIES
  (voir ci-dessus) et le toggle est reellement cable
  (`SuraSelectionScreen`/`setRecitationMode`) - reste a verifier
  visuellement sur device que le changement de couleur/offset est bien
  observable en pratique (le test live effectue cette passe etait en
  وضع التلاوة mais l'audio de test ne contenait pas d'erreur de
  prononciation deliberee).
- `ensurePipelineLoaded()`/navigation "browse" (`jumpToSura`) : recharge
  l'INTEGRALITE du pipeline (Zipformer + segmenteur, ~2-3s mesures) meme
  pour simplement consulter la liste des sourates sans jamais reciter -
  couplage accepte pour cette passe (partage le meme cache `models` que le
  micro), pas separe en un chargement plus leger. Desormais charge des le
  lancement (voir (a) ci-dessus), le cout est donc paye une fois, tot.
- `SettingsScreen` : "تلاوة الخلفية" reste un controle VISUEL uniquement
  (voir sa KDoc de fichier) - le theme, lui, est desormais reellement
  fonctionnel (voir ci-dessus), et le curseur de taille de texte a ete
  retire plutot que laisse decoratif.
- Marqueurs de fin de verset inline ("(١)" etc.) : non applicable au nouvel
  ecran mushaf (les numeros de verset sont deja des bulles dediees,
  `AyahNumberBadge` - contrairement a l'ancienne carte compacte qui n'en
  avait pas).
- Ecran `hidden_memorization` (mockup Stitch existant, non porte) : hors
  perimetre - mode de memorisation gamifie distinct, sans equivalent dans
  Dani_app.
- Navigation manuelle a 5 ecrans (`MainActivity`, sealed `Screen`) : pas de
  Navigation-Compose (nouvelle dependance evitee pour ce perimetre) - pas
  de back-stack reel, pas de deep links, pas d'animation de transition.
- `SessionHistoryStore` : JSON plat, pas de limite de taille/purge - a
  reconsiderer si le volume de sessions devient reellement important (hors
  de proportion pour l'instant).
