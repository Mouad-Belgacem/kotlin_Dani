# core-audio

Capture microphone (`AudioRecord`), mise en buffer glissant, et decoupage en
chunks pour le reste du pipeline.

Pas d'equivalent direct dans `Dani_app` (le web utilisait `pcm-worklet.js` /
`record.js` cote navigateur) - c'est la brique specifique au portage Android.
Declare `RECORD_AUDIO` dans son propre manifeste (fusionne dans celui de
l'app par Gradle), pour garder la permission localisee au module qui en a
besoin.

## Discipline de test : device physique, pas d'emulateur

Meme principe que `core-logic` (jamais de logique non executee reellement),
adapte au fait que ce module touche des API Android reelles
(`AudioRecord`, et plus tard `core-fbank`/`core-asr` : ONNX Runtime Android,
JNI `kaldi-native-fbank`) - decision actee : **tests instrumentes sur un vrai
device (Samsung Galaxy Note10, SM-N970F, Android 12/API 31, arm64-v8a)**,
pas un emulateur. Deux raisons :
- l'emulateur n'est de toute facon pas disponible dans l'environnement de
  build (virtualisation materielle non exposee a la VM) ;
- c'est preferable pour ce projet precisement : le comportement numerique
  fin (precision Fbank/ONNX) doit etre valide sur le vrai materiel ARM
  cible, pas une image x86 emulee qui pourrait diverger.

`DeviceSmokeTest.kt` (`src/androidTest/`) ne teste encore rien de
fonctionnel - juste que `./gradlew :core-audio:connectedAndroidTest` compile,
installe et execute reellement sur le device. Deux blocages rencontres et
resolus en le mettant en place, a retenir pour la suite :
- **ecran verrouille** -> l'installation/execution du test instrumente
  echoue silencieusement ("Failed to receive the UTP test results") si
  l'ecran du device est verrouille (verrouillage securise, `adb` ne peut pas
  le lever tout seul) - il doit rester deverrouille et allume pendant les
  sessions de test ;
- **"Verifier les applications via USB"** (Options pour les developpeurs) ->
  bloque l'installation de l'APK de test avec
  `INSTALL_FAILED_VERIFICATION_FAILURE` si actif - doit rester desactive.

## Etat d'avancement

**`AudioCapture` : implemente et valide sur device reel.**
`AudioCapture.start(context)` retourne un `Flow<FloatArray>` de chunks PCM
float32 dans `[-1, 1]`, a 16 kHz mono (format attendu par `core-fbank` en
aval), taille de chunk configurable (`AudioConfig.chunkSamples`, defaut 1600
= 100ms, meme granularite que `PAD_SAMPLES`/`MIN_SEGMENT_SAMPLES` cote
Dani_app). Verification permission (`RECORD_AUDIO`) avant demarrage -
exception claire (`AudioPermissionMissingException`) si absente, la demande
UI elle-meme (dialogue systeme) reste la responsabilite du module `app`.

Valide par `AudioCaptureDeviceTest` (`src/androidTest/`, execute sur le
SM-N970F - `GrantPermissionRule` accorde `RECORD_AUDIO` automatiquement, pas
de dialogue a gerer) : **4/4 tests passent**, format/taille de chunk
verifies, cycle start/stop/restart propre (le second `start()` apres un
`stop()` fonctionne sans exception - preuve que l'`AudioRecord` precedent est
bien libere), un RMS mesure sur 1s de capture reelle confirmant un signal
micro non-nul (`~0.0034`-`~0.0036` en silence ambiant - preuve que le
pipeline capture vraiment de l'audio, pas des zeros de facade), et le
contrat "gapless" (voir plus bas) mesure directement plutot que suppose :
`totalSamples/expectedSamples ratio=1.034` sur ~3s de capture reelle.

### Contrat "gapless" et relation avec le fenetrage modele - verifie, pas suppose

`AudioCapture` ne garantit qu'une chose au consommateur : un flux
**gapless et strictement ordonne** de chunks PCM, sans position cumulative
exposee. Deux points verifies explicitement (pas supposes) avant d'attaquer
`core-fbank` :

1. **Le decoupage 100ms de `AudioCapture` est independant du decoupage
   770ms du modele Zipformer.** Verifie contre
   `scripts/streaming_pipeline_standard.py::SingleStreamBuffer` : chaque
   appel au modele consomme `chunk_size*2 + PAD_LENGTH` = 77 frames Fbank,
   mais le pointeur n'avance que de `chunk_size*2` frames entre deux appels
   - les `PAD_LENGTH` (13) dernieres frames d'un appel sont re-consommees
   comme tete du suivant (chevauchement volontaire, pas un decoupage en
   tranches disjointes). Ce fenetrage vit dans `core-asr`/`core-pipeline`,
   a l'echelle des frames Fbank - `core-audio` n'a pas besoin de le
   connaitre.
2. **Pas besoin de position cumulative cote `core-audio`.** Verifie contre
   `scripts/compare_fbank_parity.py` : la brique Fbank retenue pour Android
   est `kaldi_native_fbank.OnlineFbank`, concue pour recevoir des chunks de
   taille quelconque via des appels repetes a `accept_waveform()` - c'est
   elle qui maintient sa position/son historique cumulatif en interne. Le
   `Flow<FloatArray>` de chunks independants suffit, a condition qu'il soit
   gapless (voir `deliversSamplesWithoutGapsOverASustainedCapture` dans
   `AudioCaptureDeviceTest`).
