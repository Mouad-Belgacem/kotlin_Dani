# core-segmenter

Detection d'activite vocale (VAD) via le segmenter allege (`segmenter/final/`
a la racine - `Wav2Vec2BertForAudioFrameClassification`, 3 couches au lieu de
24, meme frontend Fbank que l'original : `feature_projection_input_dim=160`
verifie dans `config.json`).

**Implemente et valide numeriquement sur device reel.**

## Role dans la boucle (endpointing)

Port de la logique `process_chunk` / `Session` de `Dani_app/api/pipeline.py` :

1. A chaque chunk, rescanne l'integralite du buffer audio roulant (pas
   seulement la fin) et retourne la liste des intervalles de parole - port de
   `extract_speech_intervals` + `remove_small_speech_intervals`
   (`recitations_segmenter/segment.py`, algorithme verifie : argmax par frame
   + detection de transitions + filtrage par duree minimale, aucun etat
   necessaire, entierement portable).
2. **Declencheur de coupure** : `len(intervals) > 1` - au moins deux segments
   de parole distincts detectes (pause complete confirmee par une reprise),
   jamais un simple silence sans confirmation de reprise. Ce signal pilote le
   reset synchronise de `core-asr` (98 tenseurs d'etat + decodeur CTC) - voir
   `core-asr/README.md`.
3. Calcule `wasl` (bool) : le recitant est-il probablement encore en train de
   parler (fin du dernier intervalle proche de la fin du buffer, un seul
   intervalle present) ? Transmis tel quel a `Corrector.correct()` dans
   `core-logic` - **ne sert qu'a** (a) inhiber la recherche globale de
   secours tant que la position n'est pas stabilisee, (b) colorer le reliquat
   de la fenetre de reference non atteint par la transcription. Ne sert PAS a
   choisir une forme phonetique waqf/wasl differente pour la reference - ce
   comportement "coincidental" de `Dani_app` est repris tel quel pour l'instant
   (decision actee ; amelioration eventuelle traitee separement plus tard).

C'est un pattern d'endpointing standard (cf. NVIDIA Riva, et des travaux de
recherche l'appliquant a des encodeurs Zipformer) - pas une improvisation.

## Export ONNX

`scripts/export_segmenter_onnx.py` (a la racine du repo) charge
`segmenter/final/` (deja present localement - contrairement a Zipformer,
aucune machine distante necessaire) et l'exporte en float32 (pas bfloat16 -
`_SEGMENTER_DTYPE` cote Dani_app est une optimisation de service GPU, pas une
contrainte du modele ; meme choix que pour Zipformer). Graphe statique
(`input_features [1,T,160]`, `attention_mask [1,T]` -> `logits [1,T,2]`,
axe temps dynamique), pas de tenseurs d'etat. Verifie contre PyTorch sur
8s d'audio reel : **maxDiff=1.43e-5**, **0 desaccord d'argmax** sur 399
frames (le seul ecart qui compterait vraiment pour un VAD). Sortie dans
`segmenter_onnx/` (racine du repo, gitignore, ~291 Mo) : `segmenter.onnx` +
`segmenter_meta.json`.

## Frontend Fbank : reutilisation de `core-fbank`, pas un moteur separe

Avant de porter quoi que ce soit, verification empirique (pas suppose) :
`transformers.audio_utils._extract_fbank_features` (le frontend reel de
`SeamlessM4TFeatureExtractor`, utilise par `segment_audio()`) est en fait une
reimplementation numpy fidele de l'algorithme Kaldi/`torchaudio.compliance.kaldi.fbank`
(fenetre povey, `remove_dc_offset` avant preaccentuation, meme convention
d'echelle "16-bit signed integers"). `scripts/compare_segmenter_fbank_parity.py`
confirme que `kaldi_native_fbank` (deja valide et disponible via JNI dans
`core-fbank`) reproduit cette sortie a **maxDiff=4.13e-4** pres, une fois
reconfigure (`snip_edges=true`, `high_freq=0.0` au lieu de `-400.0`) et
l'entree prealablement multipliee par `2^15` - meme ordre de grandeur que
l'ecart deja mesure pour Zipformer. Consequence : **pas de nouveau moteur
Fbank ecrit** - `core-fbank` expose desormais `StreamingFbank.Config.SEGMENTER`
(voir `core-fbank/README.md`), et `core-segmenter` n'a eu a ecrire que la
normalisation/l'empilement specifiques au frontend `SeamlessM4T` :

- **`SegmenterFrontend.kt`** : Fbank (config `SEGMENTER`, entree x2^15) ->
  normalisation zero-mean/unit-variance **par bande mel, sur l'integralite du
  buffer** (`ddof=1`, floor `1e-7` - PAS les stats figees du frontend
  streaming de l'ancien ASR : le segmenter n'a pas d'etat, chaque appel
  renormalise tout depuis zero, exactement comme `SeamlessM4TFeatureExtractor.__call__`
  cote Python) -> empilement stride 2 (concatenation de paires consecutives
  de frames 80-dim en un vecteur 160-dim - un reshape row-major cote Python,
  pas un entrelacement).
- **`SegmenterSession.kt`** : session ONNX Runtime Android (meme version
  `1.20.0` que `core-asr`, deja verifiee reelle). Pas d'etat entre appels -
  contrairement a `ZipformerSession`.
- **`SpeechIntervals.kt`** : port fidele de
  `recitations_segmenter.segment.extract_speech_intervals` (detection de
  transitions silence/parole par diff, fermeture synthetique si la parole
  n'est pas refermee en fin de buffer) + `remove_small_speech_intervals`
  (filtre par duree minimale, 480 echantillons = 30ms).
- **`Segmenter.kt`** : orchestrateur, port fidele de `segment_audio()` +
  `Pipeline.segment_last_interval()` (`Dani_app/api/pipeline.py`).

## Validation numerique reelle sur le Note10

`scripts/generate_segmenter_goldens.py` rejoue `segment_audio()` +
`remove_small_speech_intervals` avec le VRAI modele PyTorch et le VRAI
feature extractor HF (jamais modifies) sur 15s de `Dani_app/audio.wav` : 3
intervalles de parole bruts (aucun filtre par la duree minimale sur cette
fenetre), exercant deja le cas `len(intervals) > 1` (declencheur de coupure).

`SegmenterGoldenDeviceTest` (`connectedDebugAndroidTest`, execute sur le
SM-N970F) rejoue le pipeline complet sur device et compare en **egalite
stricte** (pas de tolerance - contrairement a `core-asr` ou seul le texte
final compte, ici les labels et intervalles SONT la sortie du module) :

- labels par frame (749 frames) : **identiques**.
- intervalles bruts ET apres filtrage : **identiques**.

Test vert sur device physique (`BUILD SUCCESSFUL`, 1 test, 0 echec, 0
erreur, 6.17s) - pas juste "ca charge sans erreur".

## Reste a faire

- `wasl` (`(len(audio) - j_last) < PAD_SAMPLES and len(intervals) == 1`,
  cf. `process_chunk`) : arithmetique triviale sur `finalIntervals` deja
  valide, pas encore cablee ici - vit naturellement dans `core-pipeline`, qui
  connait `PAD_SAMPLES` et la taille du buffer roulant.
- Rendre l'appel pilotable de facon incrementale depuis `core-pipeline` (le
  buffer roulant de `core-audio`, pas un `FloatArray` fige comme dans ce
  test).
