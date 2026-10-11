# core-fbank

Extraction de features Fbank en streaming, via `kaldi-native-fbank` (pas de
reimplementation maison, pas de calcul Fbank integre au graphe ONNX - ecarte
explicitement, cf. contexte technique du portage Zipformer).

Configuration exacte, validee bit-a-bit contre l'entrainement (voir
`scripts/compare_fbank_parity.py` a la racine du repo) :

```
snip_edges = false
window_type = "povey"
is_librosa = false
high_freq = -400.0   # piege connu : sans lui, ecart significatif vs reference
low_freq = 20.0
dither = 0
preemph_coeff = 0.97
remove_dc_offset = true
round_to_power_of_two = true
num_bins = 80
sampling_rate = 16000
```

Consomme les chunks audio de `core-audio`, produit les frames Fbank (80
bins) attendues a la fois par `core-segmenter` (frontend Wav2Vec2-BERT,
`feature_projection_input_dim=160` = 80 bins x stride 2) et par `core-asr`
(Zipformer, `num_mel_bins=80`).

**Deux configurations depuis l'ajout de `core-segmenter`**, selectionnees via
`StreamingFbank(config: StreamingFbank.Config)` (`ZIPFORMER` par defaut,
`SEGMENTER` sinon) - meme moteur `knf::OnlineFbank`, options differentes
(voir `fbank_jni.cpp::BuildOptions(config)`) :

```
                snip_edges   high_freq   (reste identique aux deux : window=povey,
ZIPFORMER       false        -400.0       low_freq=20, dither=0, preemph=0.97,
SEGMENTER       true         0.0          remove_dc_offset=true, num_bins=80)
```

`SEGMENTER` reproduit `transformers.audio_utils._extract_fbank_features`
(`SeamlessM4TFeatureExtractor`, le frontend reel de `segmenter/final/` - voir
`Dani_app/api/pipeline.py::segment_audio`), verifie caractere par caractere
puis numeriquement (`scripts/compare_segmenter_fbank_parity.py`,
`maxDiff=4.13e-4`, meme ordre de grandeur que l'ecart deja mesure pour
`ZIPFORMER`). Piege specifique a `SEGMENTER`, gere cote appelant
(`core-segmenter`, pas ici) : `SeamlessM4TFeatureExtractor` rescale son
entree par `2^15` avant calcul ("Kaldi compliance: 16-bit signed integers") -
sans ce facteur, l'ecart en log-mel est une constante ~20.8 (2*ln(2^15)), pas
un bruit d'arrondi - facile a confondre avec une divergence de config si on
ne sait pas d'ou il vient.

## Etat d'avancement : implemente et valide numeriquement sur device reel

**Aucun artefact Android pret a l'emploi n'existe pour `kaldi-native-fbank`**
(verifie, pas suppose) : ni le depot du projet, ni `sherpa-onnx` (meme
auteur, qui l'embarque) ne publient d'AAR/Maven - leur doc documente
seulement "clonez et compilez avec le NDK". D'ou l'approche retenue :

- **NDK 27.2.12479018 + CMake 3.22.1** installes explicitement dans le SDK de
  build (absents par defaut - verifie).
- **`src/main/cpp/CMakeLists.txt`** : `FetchContent` recupere les sources de
  `kaldi-native-fbank` epinglees au tag `v1.22.3` (dernier tag stable au
  moment ou ce module a ete ecrit - verifie sur GitHub, pas suppose),
  compilees en statique (`BUILD_SHARED_LIBS OFF`) et liees dans un seul
  `.so` final (`libcore_fbank_jni.so`, + `libc++_shared.so` pour la STL
  C++ - `ANDROID_STL=c++_shared`). ABI `arm64-v8a` (device de test) et
  `armeabi-v7a`.
- **`src/main/cpp/fbank_jni.cpp`** : pont JNI ecrit a la main (rien de tout
  fait n'existe) autour de `knf::OnlineFbank` (classe
  `OnlineGenericBaseFeature<FbankComputer>`, API confirmee directement dans
  les headers du projet : `AcceptWaveform`/`NumFramesReady`/`GetFrame`/
  `InputFinished`/`Pop`) - expose seulement ce dont on a besoin, pas un
  wrapper JNI generique. La config (voir tableau plus haut) est ecrite en dur
  dans `BuildOptions()`, caractere pour caractere identique a
  `scripts/compare_fbank_parity.py`.
- **`StreamingFbank.kt`** : wrapper Kotlin (`AutoCloseable`, handle natif via
  `Long`), `System.loadLibrary("core_fbank_jni")`.

### Validation numerique - test dore sur device, pas juste "ca compile"

`scripts/generate_fbank_goldens.py` calcule les frames Fbank de reference
avec `kaldi_native_fbank` **Python** (meme version `1.22.3`, meme config) sur
3s de `Dani_app/audio.wav` (16kHz mono, deja au bon format - lecture seule),
one-shot ET en alimentant par chunks irreguliers non alignes sur la frame
(1600/733/2001/512/1337 echantillons) - **confirme un ecart de 0.0 exact**
entre les deux modes de calcul cote Python (le decoupage ne doit rien
changer, verifie plutot que suppose).

`FbankGoldenDeviceTest` (`src/androidTest/`, execute sur le SM-N970F)
recharge cette fixture et rejoue les deux modes via `StreamingFbank` (donc
via le JNI/C++ compile pour ARM, pas un mock) : **2/2 tests passent**,
`maxDiff` mesure vs la reference Python = **7.74e-4** (identique en one-shot
et en chunks irreguliers) - un ecart d'arrondi float32 coherent avec deux
compilateurs/plateformes differents (wheel Python Windows x86_64 vs binaire
NDK ARM), plusieurs ordres de grandeur en dessous de ce qu'un ecart de
configuration produirait.

### Ce qui reste a faire

`core-fbank` calcule des frames Fbank correctes a partir d'un flux
`accept_waveform()` cumulatif - il ne sait pas encore s'interfacer avec le
`Flow<FloatArray>` de `core-audio` (pas de classe d'orchestration
streaming-continu encore ecrite ; aujourd'hui `StreamingFbank` est pilote
manuellement dans les tests). Ce cablage vit naturellement dans
`core-pipeline`, avec le fenetrage "modele" (`chunk_size*2 + PAD_LENGTH`
frames, chevauchement de `PAD_LENGTH`) documente dans
`core-audio/README.md`.
