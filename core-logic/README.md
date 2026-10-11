# core-logic

Module pur Kotlin/JVM — zero dependance Android. Regroupe le portage de trois
briques de `Dani_app/tilawa_correction` :

- **tokenizer** — port de `scripts/run_aware_tokenizer.py` (`RunAwareTokenizer`) :
  encodage/decodage greedy longest-match sur `vocab_run_aware.json`.
- **phonetizer** — port de `track_phase1.py` (regles T1/T2/T3 en cascade + suivi
  d'index `track`) et de `bab_alhuruf.py` (handlers par lettre appliquant les
  regles de tajwid : durees de madd via `qiraat_config.py`, ikhfa, idgham,
  iqlab, qalqala...).
- **correction** — port de `Correction.py` : `ModifiedMatcher` (variante de
  `difflib.SequenceMatcher`), recherche FM-index (`Finder`), et la machine a
  etats `Corrector.correct()` (cursor, score, settled).

Regroupes en un seul module Gradle par choix deliberer (moins de charge de
gestion pour un projet solo) plutot qu'en trois - un split reste peu couteux
plus tard si besoin, contrairement a la frontiere JVM-pur/Android qui, elle,
est structurante des le depart.

## Regle non negociable : test dore avant "termine"

Pour chaque brique portee ici, "ca compile et ca semble faire la meme chose"
**n'est jamais un critere de fin de tache**. Le critere est : un test dore
(golden test) qui compare la sortie Kotlin a la sortie Python reelle de
`Dani_app`, sur un jeu de cas representatifs, **ligne par ligne**.

Cette discipline a deja fait ses preuves sur le travail Zipformer (streaming,
export ONNX, extraction Fbank) - le piege `high_freq=-400.0` de la config
Fbank ne se serait jamais revele sans comparaison chiffree directe contre la
reference. Le phonetiseur en particulier (regles regex en cascade + handlers
par lettre + configs de duree de madd) a largement la complexite necessaire
pour cacher des bugs de portage silencieux. Ne jamais le valider a l'oeil.

**Mecanique attendue** (a affiner brique par brique, mais le principe reste) :
1. Generer les fixtures de reference en executant le code Python de
   `Dani_app/tilawa_correction` tel quel (jamais modifie) sur un jeu de
   versets/cas representatif - y compris des cas limites deja identifies dans
   le code source (ex. les tests integres a `run_aware_tokenizer.py` : 6 alifs
   -> 1 token, 8 alifs -> pas de token exact donc decomposition sans crash).
2. Sauvegarder ces sorties de reference comme fixtures versionnees (a cote des
   tests, format a definir - JSON/texte simple).
3. Le test Kotlin correspondant charge la fixture et compare caractere par
   caractere / id par id / couleur par couleur a la sortie Kotlin.
4. Un ecart = bug de portage a corriger, jamais une fixture a "ajuster" pour
   faire passer le test.

`src/test/` est reserve a ces tests - executable en JUnit desktop pur, sans
emulateur, ce qui est precisement pourquoi ce module est isole du reste.

## Etat d'avancement

- **phonetizer : porte et valide.** `phonetize()` (phase 1 `ph1T` + phase 2
  `process_phonetic_text`/handlers par lettre) reproduit a l'identique la
  sortie de `Correction.phonetize()` sur les 6330 versets de `moshaf.json`,
  pour `attach_lawasiq` True et False (12660 verifications, texte phonetique
  ET tableau `track` compares caractere par caractere/entier par entier).
  Fixtures generees par `scripts/generate_phonetizer_goldens.py` et
  `scripts/export_phonetizer_rules.py` (les tables T1/T2/T3 sont chargees
  depuis `phonetizer_rules.json`, serialise mecaniquement depuis le Python -
  jamais retranscrites a la main, pour eliminer le risque de coquille sur
  ~150 regles). Verifie par execution reelle (compilateur Kotlin standalone,
  hors Gradle - le wrapper n'existe pas encore dans ce squelette), pas
  seulement relu : les deux bugs suivants ont ete trouves et corriges par le
  test dore, pas a la relecture :
  - la numerotation des groupes de capture Java se decale des qu'une regle
    contient elle-meme un groupe capturant (aucun equivalent de
    `match.lastgroup` de Python en Java 17) ;
  - `track` et le texte n'ont pas toujours la meme longueur d'une etape a
    l'autre dans le Python original lui-meme (verifie : verset 2:32, sortie
    de l'etape T2) - un comportement reel a reproduire, pas a "corriger".
- **tokenizer : porte et valide.** `RunAwareTokenizer` (encode greedy
  longest-match + decode) reproduit `scripts/run_aware_tokenizer.py` sur 6053
  textes uniques (les 6330 sorties phonetiques du Coran + les cas limites du
  script d'origine - 6/8 alifs consecutifs). 6053/6053 verifications
  (ids ET texte decode).
- **ModifiedMatcher : porte et valide.** `find_longest_match`/
  `get_matching_blocks`/`ratio` reproduisent `Correction.ModifiedMatcher` sur
  702 paires (a,b) - sorties phonetiques reelles + mutations synthetiques
  (substitutions, troncatures, changements de longueur de repetition) via
  `scripts/generate_matcher_goldens.py`. 1386/1386 verifications. Bug trouve
  par le test dore : `get_matching_blocks()` ne doit PAS reconstruire `b2j` -
  Python le construit une seule fois a `set_seq2`, sur la version condensee
  de `b`, jamais retouche ensuite (meme quand `self.a`/`self.b` sont
  temporairement reassignes a leurs formes condensees).
- **Finder : porte et valide, avec UNE limitation actee.** Remplace la
  matrice dense `occ` (110 Mo) et les tableaux denses
  `index_to_aya*`/`index_to_start_word`/`index_to_end_word` par des listes de
  positions/frontieres + recherche binaire (voir
  `scripts/build_android_assets.py` - assets `core-logic/src/main/resources/finder/`,
  7,2 Mo au lieu de >150 Mo, meme algorithme FM-index). Valide a deux niveaux
  via `scripts/generate_finder_goldens.py` :
  - niveau intermediaire (`occAt`) : 6994/6994 contre la matrice dense
    d'origine, y compris l'indexation negative numpy `occ[c,-1]` ;
  - niveau comportemental : `verseToCursor` 168/168, `getFragment`/
    `refinedUthmani` 482/482, `getAya`/`getCandidatAyas` 389/392 sur le
    resultat qui compte (`getAya`) - 8033/8036 verifications au total (99,96%).
  Bug trouve par le test dore : une fenetre de recherche sans correspondance
  (`First=Last=-1`) n'est PAS ignoree par le Python original -
  `np.arange(-1,0)=[-1]` puis `self.sa[-1]` (indexation negative numpy)
  ajoute un vote parasite pour l'aya en toute fin de corpus ; sans ce vote,
  `get_candidat_ayas` divergeait des qu'une fenetre bruitee ne matchait rien.
  **Limitation actee (option 1, decision utilisateur)** : `np.argsort(-counts)`
  (tri des candidats a egalite de votes) utilise le quicksort par defaut de
  numpy, qui n'est PAS stable - verifie directement, l'ordre entre candidats
  a egalite est un artefact de son introsort interne (C), non documente comme
  contrat stable. Le tri stable cote Kotlin donne le meme ensemble et le meme
  rang pour toute paire non a egalite ; seul l'ordre relatif entre egalites
  peut differer, ce qui n'affecte `getAya()` que dans de rares cas ou une
  egalite tombe pile a la frontiere de troncature (`k=30`) - 3/392 cas dans
  l'echantillon de test (volontairement tres bruite pour stresser
  l'algorithme).
- **Corrector : porte et valide.** `correct()` (machine a etats -
  cursor/score/settled/stops evoluent d'un appel a l'autre) rejoue 25
  sessions completes (250 appels `correct()` au total, versets consecutifs
  avec injection d'erreurs synthetiques sur certains, pattern partiel/complet
  qui imite `Dani_app/api/pipeline.py::process_chunk`) via
  `scripts/generate_corrector_goldens.py`. **250/250 verifications** (texte,
  couleurs, additions, ET tout l'etat interne - cursor/refCursor/score/
  settled/stops - a chaque etape). Deux ecarts deliberes documentes en
  commentaire dans `Corrector.kt` : le constructeur Python `finder=None` est
  une coquille (`==` au lieu de `=`) jamais declenchee en pratique (`finder`
  est ici un parametre requis, pas nullable) ; `get_color()` et `__call__`
  (colorisation ANSI terminal) sont du code mort/sans consommateur reel, non
  portes.

**Note commune a toutes les briques** : JUnit ne tourne pas encore
*reellement* dans cet environnement (pas de Gradle/JDK Android complet ici) -
tous les `*GoldenTest.kt` sont ecrits pour tourner via Gradle des que le
wrapper existe ; la validation ci-dessus vient de runners standalone
equivalents, compiles et executes directement avec `kotlinc` (compilateur
Kotlin telecharge dans le scratchpad de session, hors du repo). A revalider
sous `./gradlew :core-logic:test` la premiere fois que Gradle tourne pour de
vrai sur ce module.
