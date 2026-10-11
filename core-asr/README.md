# core-asr

Session ONNX Runtime pour `zipformer_streaming_onnx_standard/zipformer_warsh_standard_streaming.onnx`
(a la racine du repo), et decodeur CTC streaming associe.

## Contrat ONNX (`streaming_meta.json`)

- Entrees : `x` (frames Fbank, `float32`), `x_lens` (`int64`), `state_0`..`state_97`.
- Sorties : `log_probs`, `out_lens`, `new_state_0`..`new_state_97`.
- `chunk_size=32`, `left_context_frames=256`, `pad_length=13` ->
  chaque appel consomme `raw_chunk_len = chunk_size*2 + pad_length = 77` frames
  Fbank brutes (le `*2` vient du sous-echantillonnage x2 du frontend, avant que
  `chunk_size` s'applique). Latence algorithmique incompressible d'environ
  130ms (le `pad_length`) en plus du chunk lui-meme (~640ms).
- 98 tenseurs d'etat = 16 couches d'encodeur x 6 tenseurs chacune
  (`cached_key`, `cached_nonlin_attn`, `cached_val1`, `cached_val2`,
  `cached_conv1`, `cached_conv2` -> `state_0..95`) + `cached_embed_left_pad`
  (`state_96`) + `processed_lens` (`state_97`). Shapes/dtypes exacts dans
  `streaming_meta.json`, tous initialises a zero au premier appel.

**Piege obligatoire** : `processed_lens` est declare `int32` en entree, mais
`new_processed_lens` sort en `int64` (promotion de type interne au modele).
Recaster **systematiquement les 98 etats** vers leur dtype d'entree avant
reinjection au chunk suivant (pas seulement `processed_lens`, par precaution
contre d'autres promotions non detectees) - sinon ONNX Runtime leve une
erreur de type des le deuxieme chunk.

## Decodeur CTC streaming

1. `argmax` par frame sur `log_probs` (dimension 92 = taille du vocab
   run-aware).
2. Collapse CTC **avec etat inter-chunks** : le dernier label emis
   (`prev_label`) doit survivre d'un chunk a l'autre - sans ca, deux
   occurrences consecutives d'un meme symbole a cheval sur deux chunks
   seraient comptees comme deux occurrences distinctes au lieu d'une seule
   fusionnee. Blank = id 0.
3. Les ids collapses (accumules dans `token_ids`) sont decodes via
   `core-logic`'s `RunAwareTokenizer` (greedy longest-match - plusieurs ids
   representent des sequences de plusieurs caracteres, ex. un seul id pour
   `اااااا`).

Reference Python : `scripts/streaming_pipeline_standard.py`
(`StreamingCTCDecoder`), `scripts/export_streaming_onnx_standard.py`.

## Reset synchronise avec `core-segmenter` (endpointing)

Decision actee : quand `core-segmenter` declenche une coupure
(`len(intervals) > 1`) :

- les 98 tenseurs d'etat **et** `token_ids`/`prev_label` du decodeur CTC sont
  remis a zero **ensemble, au meme instant** ;
- **pas de reinjection** de l'audio retenu (le petit bout apres le point de
  coupure) dans le cache fraichement reinitialise - contrairement a l'ancien
  systeme wav2vec2-bert de `Dani_app` (qui rejouait ce reliquat dans un
  "logits ring" rejouable). Le CTC de Zipformer est cumulatif et irreversible
  - on accepte un contexte legerement plus froid pendant les ~640ms qui
    suivent une reprise, contre une synchronisation etat-ASR/etat-decodeur
    beaucoup plus simple et sure.
- l'etat du `Corrector` (`core-logic`) n'est **jamais** touche par cette
  coupure - machine a etats separee, persistante sur toute la session.

Un artefact mineur connu et accepte : un caractere residuel peut apparaitre
en toute fin de flux (effet de bord du dernier etage de sous-echantillonnage),
negligeable en usage reel (n'apparait qu'a l'arret volontaire de
l'enregistrement). Pas d'investigation prevue pour l'instant.

## Implementation et validation sur device reel

`onnxruntime-android` verifie comme artefact Maven Central reel (pas suppose)
via `maven-metadata.xml` : versions `1.11.0` -> `1.29.0` disponibles au moment
ou ce module a ete ecrit. `1.20.0` retenue (stable, le graphe exporte en
`opset_version=13` tourne deja sous ONNX Runtime Python 1.23.2, meme coeur
C++ que le binding Android). API Java verifiee sur la Javadoc officielle
(`OnnxTensor`, `TensorInfo`/`OnnxJavaType`, `OrtSession`/`OrtSession.Result`),
pas devinee.

- `StreamingMeta.kt` : parsing de `streaming_meta.json` (aucune valeur codee
  en dur cote Kotlin).
- `ZipformerSession.kt` : session ORT + 98 tenseurs d'etat initialises a
  zero avec shape/dtype exacts. Chaque etat de sortie est copie vers un
  tenseur frais et recast vers son dtype d'entree (verifie sur le graphe
  reel : seul `new_state_97`/`processed_lens` sort en `int64` alors que
  `state_97` est attendu en `int32` - les 97 autres sortent deja en
  `float32`, mais le recast reste systematique par precaution). Jamais de
  reutilisation directe d'un tenseur possede par `OrtSession.Result` (sa
  fermeture libererait la memoire native).
- `ZipformerChunker.kt` : port fidele de la boucle de fenetrage de
  `scripts/generate_asr_goldens.py` (elle-meme le pendant numpy de
  `SingleStreamBuffer`) - `x_lens` envoye au modele est **toujours** la
  longueur post-padding (`chunk_length`), jamais la longueur reelle
  pre-padding, verifie directement dans le script Python de reference
  (`feat_len += pad_amount` avant construction de `x_lens`).
- `StreamingCtcDecoder.kt` : collapse CTC avec etat inter-chunks (voir
  ci-dessus).

**Validation numerique reelle sur le Note10** (`AsrGoldenDeviceTest`,
`./gradlew :core-asr:connectedDebugAndroidTest`) : rejoue les 5 premieres
secondes de `Dani_app/audio.wav` a travers le pipeline complet
`StreamingFbank` (core-fbank, deja valide) -> `ZipformerChunker` ->
`ZipformerSession` -> `StreamingCtcDecoder`, sur le vrai modele `.onnx`
(248 Mo, copie dans `src/androidTest/assets/model/`, jamais commite) charge
via ONNX Runtime Android, et compare a la reference generee cote Python par
`scripts/generate_asr_goldens.py` (qui rejoue lui-meme exactement la boucle
de `scripts/verify_onnx_export_standard.py`) :
- 9 chunks, `outLen=16` a chaque chunk : identique a la reference Python.
- `log_probs` : ecart max **`1.09e-4`** (arrondi float32 ORT desktop vs ORT
  mobile, meme moteur - un etat mal recast ou un fenetrage faux donnerait un
  ecart de plusieurs ordres de grandeur au-dessus).
- Texte decode et ids CTC post-collapse : **egalite stricte** avec la
  reference (`'بِسمِللَااهِررَحمَاانِررَحِےےےےم'`, 24 ids) - pas de
  tolerance sur cette partie, c'est la garantie qui compte en usage reel.

Test vert sur device physique (pas juste "ca charge sans erreur").

## `StreamingZipformerEncoder` : le pilote VRAIMENT incremental (pour `core-pipeline`)

`ZipformerChunker`/`AsrGoldenDeviceTest` operent en mode BATCH : le buffer de
frames est deja entierement connu a l'avance, avec un padding de queue fixe
(`pad_length + TAIL_PAD_LEN`) pour clore le tout dernier chunk. `core-pipeline`
a besoin d'un mode reellement streaming - `StreamingZipformerEncoder.kt`
consomme les frames au fil de l'eau (`pushAudio()`) et ne forme une fenetre
que lorsqu'elle est integralement disponible depuis de l'audio REEL, sans
jamais de padding. Deux consequences, toutes deux VERIFIEES sur device (pas
juste avancees comme hypotheses) :

1. **Latence de fin de flux.** Alimente par les 5s du golden `core-asr` en
   chunks de 1600 echantillons (`StreamingEncoderGoldenDeviceTest`), il
   n'atteint que 22/24 tokens - un PREFIXE EXACT de la reference batch. Ce
   n'est pas un defaut : les 2 derniers tokens dependent d'une fenetre que
   seul le padding de queue du mode batch peut completer (verifie
   arithmetiquement : sur ces 5s, 7 fenetres sont formables sans aucun
   padding - pointeurs 0,64,...,384 - la 8e a besoin de frames au-dela de la
   frame 500, qui n'existent pas dans ce clip).
2. **`flush()` referme exactement cet ecart.** Plutot que de laisser
   l'explication ci-dessus comme une hypothese plausible non testee,
   `StreamingEncoderFlushGoldenDeviceTest` la verifie concretement : meme
   alimentation incrementale, puis un appel a `flush()` (qui applique le
   MEME padding LOG_EPS de queue - `pad_length + TAIL_PAD_LEN` frames - que
   `scripts/generate_asr_goldens.py`) - resultat : **24/24 tokens exacts**,
   texte decode identique caractere pres a la reference batch. `flush()`
   n'est PAS appele a chaque chunk (ca romprait la latence documentee
   ci-dessus) - c'est exactement ce qu'un flush de fin de session reelle
   (utilisateur qui arrete l'enregistrement) devra faire.

Test vert sur device physique (3/3 tests core-asr, dont ce dernier).

## Reste a faire

- Reset synchronise etat ASR + decodeur CTC sur signal de `core-segmenter`
  (decision deja actee ci-dessus, cablee dans `core-pipeline` -
  `PipelineSession.processChunk()` appelle `encoder.resetState()` sur
  `cut`).
