# core-pipeline

Orchestrateur - equivalent Kotlin de `Session` + `process_chunk()` dans
`Dani_app/api/pipeline.py`. Ne reimplemente aucune logique metier lui-meme :
cadence les appels aux autres modules et porte l'etat de session.

**Implemente et valide fonctionnellement sur device reel** (`PipelineSession.kt`,
`processChunk()`).

Boucle par chunk audio :

```
core-audio (chunk)
   -> core-fbank (frames Fbank streaming)
        -> core-asr.pushAudioToEncoder(chunk)      // encode, jamais rejoue
        -> core-segmenter.segmentLastInterval(buffer)  // rescanne tout le buffer roulant
             -> cut = intervals.size > 1 ?
                  oui -> core-asr.resetState() + core-pipeline coupe le buffer audio
                         (Corrector NON touche)
             -> wasl = ...
        -> tilawa = core-asr.decodeFrames(...)     // si segment assez long
   -> core-logic.Corrector.correct(tilawa, wasl)
   -> evenement expose a l'UI (app) : texte, couleurs, position, score
```

Etat de session porte ici (miroir de `Session` cote Python) : buffer audio
roulant, position d'ecriture des frames, et le `Corrector` de `core-logic`
(qui, lui, persiste independamment des resets `core-asr`/`core-segmenter`
declenches par l'endpointing - voir `core-asr/README.md`).

Pas de logique de tajwid/correction ici - tout ce qui est algorithme pur vit
dans `core-logic` et doit passer par sa discipline de tests dores avant
d'etre considere fiable.

## Quatre ecarts DELIBERES par rapport a Dani_app

- **Reset sur coupure = pas de rejeu de l'audio retenu** (deja acte lors du
  design de `core-asr`). Le "logits ring" rejouable de l'ancien
  wav2vec2-bert (`decode_frames(first_frame,last_frame)`, `cut_at()` qui
  re-encode le reliquat sous un cache frais) n'a pas d'equivalent ici : le
  CTC de Zipformer est cumulatif/irreversible. Sur coupure
  (`finalIntervals.size > 1`), `core-asr` et le buffer audio roulant sont
  remis a zero ENSEMBLE, sans rejeu - `encoder.text()` est deja exactement
  le texte du segment courant, puisqu'un reset a lieu a chaque frontiere de
  segment (voir `core-asr/README.md`).
- **Pas d'alignement de coupure sur une frontiere de frame**
  (`SAMPLES_PER_FRAME` cote Python) : ce port n'indexe jamais de position
  absolue par frame, donc cet alignement n'a plus d'objet.
- **Segmenteur borne aux `SEGMENTER_WINDOW_SECONDS` (4s) les plus recentes
  du buffer, pas son integralite** (ajoute apres mesure reelle de latence
  cote `app`, pas une decision de conception a priori ; reduit de 10s a 4s
  apres une premiere mesure) : `core-segmenter` rescanne l'INTEGRALITE du
  buffer roulant a chaque appel dans Dani_app - chronometrage reel
  (`ChunkStageTiming`, voir `app/README.md`) montre que ce cout domine
  (85-88% du temps total selon la mesure) et croit sans borne avec la duree
  du buffer (jusqu'a 30s). Borner sa fenetre borne directement cette
  croissance (confirme aux deux tailles de fenetre : le cout PLATEAU une
  fois la fenetre pleine, au lieu de continuer a croitre - voir
  `app/README.md` pour les chiffres avant/apres). Contrepartie ACCEPTEE :
  un enonce plus long que la fenetre peut avoir son debut reel hors
  fenetre, jamais vu par le segmenteur.
- **Appel au segmenteur throttle a `SEGMENTER_CALL_INTERVAL_SAMPLES` (200ms),
  pas a chaque chunk core-audio (100ms)** - complementaire au fenetrage
  ci-dessus, pas un remplacement. `core-asr`, lui, voit TOUJOURS chaque
  chunk sans exception (seul l'appel au segmenteur saute un chunk sur
  deux). Confirme reel : 78/157 chunks etaient de vrais appels segmenteur
  sur un test acoustique (quasi exactement 50%).

Ces deux derniers ecarts sont reproduits EXACTEMENT par
`scripts/generate_pipeline_goldens.py` (meme discipline que partout
ailleurs dans ce portage) - le golden a ete regenere a chaque changement et
`PipelineSessionGoldenDeviceTest` reste vert en egalite stricte a chaque
fois (52/52 avec la fenetre a 10s seule, 30/30 avec fenetre 4s + throttle
200ms - la baisse du nombre de resultats confirme que le throttle change
reellement le comportement observable, pas un no-op).

Champs de debug non portes (`fragments`/`win`/`path`/`timings` du dict
retourne par `process_chunk()`) : introspection dev (patch temporaire de
`finder.get_fragment`), sans consommateur reel cote mobile.

## Composants portes

- **`PipelineConfig.kt`** : constantes reellement utilisees par
  `process_chunk()` (`PAD_SAMPLES`, `MIN_SEGMENT_SAMPLES`,
  `MIN_TILAWA_CHARS`, `SEGMENT_MATCH_TOLERANCE`, `MAX_BUFFER_SECONDS`).
- **`AddHarakat.kt`** : port de `add_harakat()` (mode `help_tilawa`,
  `offset==2` a la creation de session). Regles chargees depuis
  `harakat_rules.json`, extrait MECANIQUEMENT (points de code, jamais
  retranscrit a la main) du corps de la fonction Python - risque de
  transcription trop eleve sur des marques diacritiques arabes isolees.
  Valide par `AddHarakatTest` (JVM, fixtures generees par la vraie fonction
  Python).
- **`BismillahIstiadhaGuard.kt`** : port du garde-fou de fin de
  `process_chunk()` (score remis a 0 et resultat ignore si le texte choisi,
  avant `settled`, est un sous-texte de la Basmala/Istiadha). Memes chaines
  de reference extraites mecaniquement (`bismillah_istiadha.json`). Valide
  par `BismillahIstiadhaGuardTest` (JVM) contre 17 cas generes par
  `scripts/generate_bismillah_istiadha_goldens.py` - pas seulement le cas
  nominal (sous-textes de la reference), mais des cas limites REELS de
  tajwid extraits mecaniquement du vrai `Finder` :
  - **At-Tawbah (9:1) n'a pas de Basmala** - le vrai texte uthmani de ce
    verset (et ses premiers mots seuls, le cas realiste ou l'ASR ne
    transcrit qu'un fragment) est verifie NE PAS declencher le garde-fou.
  - **Al-Fatiha (1:1)**, decouverte en generant la fixture : dans la
    convention de decoupage de ce `Finder`, le premier verset concatene la
    Basmala ET la suite ("Al-hamdu...") - le texte complet de 1:1 n'est donc
    PAS un sous-texte litteral de la seule Basmala (verifie par le vrai `in`
    Python, pas suppose a l'avance).
  - Quasi-collisions (Basmala inversee, un caractere supprime/ajoute au
    milieu, diacritiques retires) : confirment que le garde-fou reste
    strict, sans tolerance implicite.
- **`StreamingZipformerEncoder.kt`** (vit dans `core-asr`, pas ici - c'est le
  module qui possede deja `StreamingFbank`/`ZipformerSession`/
  `StreamingCtcDecoder`) : pilote VRAIMENT incremental de l'encodeur, par
  opposition a `ZipformerChunker` (mode batch, buffer de frames deja connu a
  l'avance, utilise seulement pour generer les fixtures dorees). Consomme
  une fenetre de `chunkLength` frames uniquement quand elle est
  integralement disponible depuis de l'audio REEL - jamais de padding de
  queue synthetique (contrairement au mode batch). Consequence assumee :
  latence algorithmique de ~770ms sur `text()`, deja documentee.
- **`PipelineSession.kt`** : orchestrateur final, port fidele de `Session` +
  `process_chunk()`.

## Validation

Meme discipline que partout ailleurs dans ce portage : egalite stricte
contre une reference Python chiffree, pas "pas d'exception + resultats
plausibles". Trois niveaux :

1. **`AddHarakatTest`/`BismillahIstiadhaGuardTest`** (JVM, `testDebugUnitTest`,
   pas de device) : egalite stricte contre des fixtures generees par le vrai
   code Python - 17 cas Bismillah/Istiadha (nominal + limites, voir plus
   haut) + fixtures `add_harakat` - tous verts.
2. **`StreamingEncoderGoldenDeviceTest` + `StreamingEncoderFlushGoldenDeviceTest`**
   (device, `core-asr`) : le pilote incremental, alimente par chunks de 1600
   echantillons (grain reel de `core-audio`), reproduit un PREFIXE EXACT
   (22/24 tokens) de la reference dore batch tant qu'aucun padding n'est
   applique - et en enchainant `flush()` (qui applique le meme padding de
   queue que la reference), on obtient les **24/24 tokens exacts**. Ce
   deuxieme point n'est pas une hypothese : il a ete verifie concretement,
   pas seulement avance comme explication plausible du prefixe (voir
   `core-asr/README.md`).
3. **`PipelineSessionGoldenDeviceTest`** (device, ce module) : reference
   Python CHIFFREE dediee, generee par `scripts/generate_pipeline_goldens.py`.
   Il n'existe pas de pipeline Python pre-existant pour cette combinaison
   precise (`Dani_app/api/pipeline.py` pilote l'ancien ASR wav2vec2-bert,
   pas Zipformer) - ce script est donc un mirroir Python DELIBERE de
   `PipelineSession.kt`, construit avec les memes briques deja validees
   individuellement : `Finder`/`Corrector` REELS et non modifies
   (`Dani_app/tilawa_correction/Correction.py`, import direct), le VRAI
   feature extractor HF + le VRAI `segmenter.onnx` pour le segmenter, et
   `kaldi_native_fbank` + le VRAI `zipformer.onnx` pour l'ASR - avec
   EXACTEMENT la meme semantique de fenetrage "stricte" (jamais de padding)
   que `StreamingZipformerEncoder.pushAudio()` cote Kotlin (PAS le mode
   batch de `generate_asr_goldens.py`). Rejoue 15s d'audio reel
   (`Dani_app/audio.wav`, meme extrait que le golden `core-segmenter`) par
   chunks de 1600 echantillons a partir de (sourate 1, verset 1), et
   enregistre **tous** les resultats produits (pas un point de controle
   ponctuel) : 52 resultats, chacun avec ses 10 champs
   (`sura`/`aya`/`wasl`/`repeat`/`settled`/`text`/`offset`/`cursor`/
   `prevCursor`/`trust`/`colors`).

   `PipelineSessionGoldenDeviceTest` rejoue exactement le meme scenario sur
   le Note10 et compare la liste entiere, champ par champ, en egalite
   stricte (`trust` a `1e-9` pres) - **52/52 resultats identiques, aucune
   tolerance necessaire** : malgre l'arrondi float32 deja mesure entre ORT
   desktop et ORT mobile (~1e-4 sur les logits, cf. `core-asr/README.md` et
   `core-segmenter/README.md`), aucune decision argmax (ASR ou segmenter)
   n'a bascule sur cet extrait, a travers ~150 chunks et plusieurs coupures
   d'endpointing. Preuve reelle, pas supposition : le test a d'abord ete
   ecrit avec des assertions structurelles laches ("pas d'exception,
   resultats plausibles"), qui ont immediatement revele un vrai angle mort
   du test (une assertion `text non vide` trop stricte, qui ne refletait pas
   le contrat reel de `Corrector.correct()` - corrige en retirant cette
   assertion, pas en l'affaiblissant arbitrairement) avant d'etre remplacees
   par cette comparaison exacte.

## Reste a faire

- Cablage reel avec `core-audio` (`Flow<FloatArray>` -> `processChunk()`) -
  ce module accepte deja des chunks `FloatArray`, il manque juste le
  collecteur de flux cote `app`.
- `PipelineSession.create()` prend un `(startSura, startAya)` fixe - pas
  encore de detection automatique du point de depart le plus probable.
