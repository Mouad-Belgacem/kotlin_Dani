# mobile_app

App Android (Zipformer streaming ASR + correction tajwid en direct), package
`com.tarteelwarsh.tilawa`. Squelette de structure - aucune logique metier
implementee pour l'instant, voir le README de chaque module pour l'etat
d'avancement et les taches restantes.

Pas une copie de `Dani_app` : ce dossier reste une reference en lecture
seule (phonetiseur, logique de comparaison/correction, `moshaf.json`). Les
briques reutilisables sont portees deliberement, module par module, jamais
copiees telles quelles.

## Modules

```
core-logic        pur Kotlin/JVM - tokenizer + phonetiseur + correction
                   (testable JUnit desktop, sans emulateur)
core-audio        Android - capture micro, buffer roulant
core-fbank        Android - extraction Fbank streaming (kaldi-native-fbank)
core-segmenter    Android - VAD (segmenter allege 3 couches), endpointing
core-asr          Android - Zipformer streaming ONNX, decodeur CTC
core-pipeline     Android - orchestrateur (equivalent Session/process_chunk)
app               Android - UI, assemblage
```

La frontiere JVM-pur (`core-logic`) / Android (le reste) est structurante et
non negociable : c'est ce qui permet de valider le portage du phonetiseur et
du correcteur par tests dores contre les sorties Python de `Dani_app`, sans
emulateur. Voir `core-logic/README.md` pour la discipline de test - elle
s'applique a chaque brique portee depuis `Dani_app/tilawa_correction`, sans
exception.

## Outillage de preparation des donnees

Vit dans `scripts/` a la racine du repo (pas ici), regroupe avec le reste de
l'outillage d'export Zipformer plutot que separe dans `mobile_app/` :
- `scripts/export_segmenter_onnx.py` - export ONNX du segmenter allege
  (n'existe pas encore, contrairement a Zipformer)
- `scripts/build_android_assets.py` - conversion des donnees FM-index
  (`Data_for_quran_search/*.npy`) vers un format binaire lisible en Kotlin

## Etat d'avancement

**`core-logic` : complet et valide.** Les 4 briques (phonetiseur, tokenizer,
`ModifiedMatcher`, `Finder` + `Corrector`) sont portees et verifiees par test
dore contre le Python reel de `Dani_app`. Voir `core-logic/README.md` pour le
detail brique par brique, les fixtures, et les ecarts deliberes/limitations
actees en cours de route (tous documentes avec leur justification). Assets
`Finder` (FM-index compact) generes dans `core-logic/src/main/resources/finder/`.

**Environnement de build/test : operationnel de bout en bout.**
- `./gradlew` genere (Gradle 8.7). `./gradlew :core-logic:test` passe
  entierement sous le vrai harnais Gradle+JUnit5 (9 tests, 0 echec) -
  dernier point de verification laisse en suspens, maintenant confirme.
- SDK Android deja present dans l'environnement (`C:\Android` -
  `platform;android-34`, `build-tools;34.0.0`) - compilation AGP reelle
  verifiee (`:core-audio:assembleDebug` -> AAR construit avec succes).
- **Pas d'emulateur** : virtualisation materielle non exposee a la VM de
  build. Decision actee : tests instrumentes sur **device physique**
  (Samsung Galaxy Note10, SM-N970F, Android 12/API 31, arm64-v8a) branche en
  USB - preferable de toute facon pour ce projet (precision numerique
  Fbank/ONNX a valider sur le vrai materiel ARM cible). Pipeline
  build -> install -> execution sur device confirme fonctionnel
  (`:core-audio:connectedAndroidTest`, voir `core-audio/README.md` pour les
  deux blocages device rencontres et leur resolution - ecran verrouille,
  verification USB des apps).

**`core-audio` : implemente et valide sur device reel.** Capture micro
(`AudioRecord` -> `Flow<FloatArray>`, 16kHz mono, `[-1,1]`), contrat gapless
verifie par mesure directe (pas suppose - ratio echantillons recus/attendus
sur capture soutenue). Voir `core-audio/README.md`, y compris la relation
(verifiee, pas supposee) entre son decoupage 100ms et le fenetrage 770ms du
modele Zipformer.

**`core-fbank` : implemente et valide numeriquement sur device reel.**
Aucun artefact Android pret a l'emploi n'existe pour `kaldi-native-fbank`
(verifie sur le depot du projet et sur `sherpa-onnx`) - compile depuis les
sources via NDK/CMake `FetchContent` (tag epingle `v1.22.3`), pont JNI ecrit
a la main autour de `knf::OnlineFbank`. Sortie comparee numeriquement a la
reference Python (`kaldi_native_fbank`, meme version, meme config) sur de
l'audio reel : ecart max `7.74e-4` (arrondi float32 entre plateformes, pas
une divergence de config), identique en alimentation one-shot et en chunks
irreguliers. Voir `core-fbank/README.md`.

**`core-asr` : implemente et valide numeriquement sur device reel.**
`onnxruntime-android` confirme comme artefact Maven Central reel (pas
suppose), `1.20.0` retenue. Session ONNX avec les 98 tenseurs d'etat geres
(init a zero, recast systematique vers le dtype d'entree apres chaque
appel), fenetrage fidele a `scripts/generate_asr_goldens.py`, decodeur CTC
streaming avec etat inter-chunks. Rejoue sur le Note10 les 5 premieres
secondes de `Dani_app/audio.wav` a travers le pipeline complet
(`core-fbank` -> `ZipformerChunker` -> `ZipformerSession` ->
`StreamingCtcDecoder`) et compare a la reference Python : `log_probs` ecart
max `1.09e-4`, texte decode et ids CTC **identiques** a la reference
(`'بِسمِللَااهِررَحمَاانِررَحِےےےےم'`). Voir `core-asr/README.md`.

**`core-segmenter` : implemente et valide numeriquement sur device reel.**
Export ONNX de `segmenter/final/` produit et verifie (`maxDiff=1.43e-5` vs
PyTorch, 0 desaccord d'argmax). Le frontend Fbank du segmenter
(`SeamlessM4TFeatureExtractor`, different de Zipformer : `snip_edges=true`,
`high_freq=0.0`, entree x2^15) s'est avere numeriquement reproductible par
`kaldi_native_fbank` reconfigure (`maxDiff=4.13e-4`) - `core-fbank` expose
donc desormais deux configs (`StreamingFbank.Config.ZIPFORMER`/`SEGMENTER`)
plutot qu'un moteur Fbank separe. Pipeline complet (frontend -> ONNX ->
extraction d'intervalles) rejoue sur le Note10 contre la reference Python
reelle (`Dani_app/api/pipeline.py::segment_audio` + `remove_small_speech_intervals`,
jamais modifies) : labels par frame et intervalles de parole **identiques**,
egalite stricte (pas de tolerance). Voir `core-segmenter/README.md` et
`core-fbank/README.md`.

**`core-pipeline` : implemente et valide numeriquement sur device reel**
(egalite stricte, meme discipline que le reste du portage). Port fidele de
`Session` + `process_chunk()` (`Dani_app/api/pipeline.py`), avec deux ecarts
deliberes deja actes (reset sans rejeu de l'audio retenu, pas d'alignement
de frame - le CTC de Zipformer est cumulatif, contrairement au "logits ring"
rejouable de l'ancien wav2vec2-bert). `core-asr` a gagne un pilote VRAIMENT
incremental (`StreamingZipformerEncoder`) : alimente par chunks de 1600
echantillons, il reproduit un prefixe exact (22/24 tokens) de la reference
batch, et un `flush()` explicite (padding de queue identique a la
reference) referme cet ecart a 24/24 EXACT - verifie concretement, pas
suppose. Comme il n'existe pas de pipeline Python pre-existant pour cette
combinaison precise (Zipformer + segmenter allege), un mirroir Python dedie
a ete ecrit (`scripts/generate_pipeline_goldens.py`, construit sur les
memes briques deja validees : Finder/Corrector reels, vrais modeles ONNX)
et rejoue sur les memes 15s d'audio : **52/52 resultats identiques, champ
par champ, en egalite stricte** avec `PipelineSession` sur le Note10 -
aucune divergence numerique n'a fait basculer de decision argmax sur cet
extrait. Voir `core-pipeline/README.md`.

**`app` : UI portee, micro reel branche - trois causes reelles de la latence
initiale identifiees et corrigees, le temps reel strict n'est pas encore
atteint.** Ecran principal desormais `ui/mushaf/MushafScreen.kt` (vraie page
de mushaf, pagination officielle 604 pages, reveal progressif porte
fidelement de `Dani_app/web/record.js` - voir `app/README.md`), pas
l'ancienne carte compacte (`live_correction`, mockup Stitch qui divergeait
du vrai theme/mapping de couleur de Dani_app, superseded). Deux inversions
RTL reelles trouvees en comparant un screenshot device au mockup, corrigees
a l'epoque de `live_correction` - **le nouvel ecran mushaf n'a pas encore
ete revalide sur device** (deconnexion USB durable du Note10, voir
`app/README.md::Reste a faire`). `core-audio.AudioCapture` ->
`core-pipeline.PipelineSession.processChunk()` -> UI, teste par bouclage
ACOUSTIQUE reel (haut-parleur du Note10 -> micro du Note10, pas un flux
simule) a chaque etape. Latence son -> affichage instrumentee et MESUREE
(pas supposee) : mesure initiale moyenne **2681ms** (max 3500ms), dont
seulement ~26ms de rendu Compose - la quasi-totalite venait du calcul
pipeline. Trois corrections reelles depuis :
1. **Decouplage capture/traitement** (inconditionnel) : `AudioCapture`
   bloquait avant sur `processChunk()`, arretant la capture micro elle-meme
   pendant chaque appel lent - un `Channel` non borne separe desormais
   capture et traitement, la capture ne s'arrete plus jamais.
2. **Chronometrage reel par etage** (pas une attribution par raisonnement) a
   confirme le segmenteur responsable de 85-88% du temps de calcul, croissant
   sans borne avec la duree du buffer (rescan integral a chaque appel,
   comportement copie de Dani_app).
3. **Fenetre segmenteur bornee (4s, apres un premier essai a 10s) +
   throttle de son appel a 200ms** (deux ecarts complementaires vs
   Dani_app, golden Python regenere et revalide en egalite stricte a
   chaque fois) : le cout PLATEAU desormais une fois la fenetre pleine au
   lieu de croitre sans fin (confirme aux deux tailles de fenetre), et le
   systeme tourne ~3,2x plus lentement que le temps reel (mesure directe
   retard-accumule/audio-ecoule) contre ~6,6x avec la fenetre a 10s seule -
   amelioration reelle d'un facteur ~2 en plus du fenetrage, elle-meme tres
   loin du ~25-35x du tout debut. Voir `app/README.md` pour le detail
   complet (donnees brutes avant/apres a chaque etape, contreparties
   acceptees, pistes restantes).

**Reste a faire :**
- Rapprocher le temps de calcul du budget 100ms/chunk (temps reel strict
  pas encore atteint, meme apres le fenetrage du segmenteur).
- `PipelineSession.create()` prend un `(startSura, startAya)` fixe - pas de
  detection automatique du point de depart le plus probable.
- Ecrans restants du mockup Stitch (selection de sourate, parametres,
  resume de session, mode revele/memorisation) : non portes, hors perimetre
  de cette passe.
