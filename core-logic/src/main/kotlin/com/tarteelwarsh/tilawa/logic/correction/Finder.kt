package com.tarteelwarsh.tilawa.logic.correction

import com.tarteelwarsh.tilawa.logic.phonetizer.phonetize
import kotlin.math.abs

// Port fidele de Dani_app/tilawa_correction/Correction.py::Finder, avec UNE
// difference deliberee et actee : la matrice dense `occ` (110 Mo) et les
// tableaux denses index_to_aya*/index_to_start_word/index_to_end_word (une
// valeur par caractere) sont remplaces par des listes de positions/frontieres
// + recherche binaire (voir scripts/build_android_assets.py) - meme
// algorithme FM-index, stockage ~15x plus petit. Valide a deux niveaux :
// FinderOccGoldenTest (occ(c,i) == valeur de la matrice dense d'origine) et
// FinderBehaviorGoldenTest (get_aya/get_candidat_ayas/get_fragment/
// refined_uthmani strictement identiques au Finder Python reel).

data class IdxRange(val start: Int, val end: Int) // [start, end), miroir des tuples Python (start, start+len)

/** Frontieres triees + recherche binaire bidirectionnelle position<->(sura,aya) - remplace un tableau dense index_to_aya*. */
class AyaIndex(private val boundaries: AyaBoundaries) {
    private val rangeOf: Map<Pair<Int, Int>, IdxRange> = buildMap {
        for (i in boundaries.starts.indices) {
            put(boundaries.suras[i] to boundaries.ayas[i], IdxRange(boundaries.starts[i], boundaries.ends[i]))
        }
    }

    /** (sura, aya) du verset contenant la position donnee - equivalent de index_to_aya*[position]. */
    fun ayaAt(position: Int): Pair<Int, Int> {
        var lo = 0
        var hi = boundaries.starts.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (boundaries.starts[mid] <= position) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return boundaries.suras[ans] to boundaries.ayas[ans]
    }

    fun rangeOf(verse: Pair<Int, Int>): IdxRange = rangeOf.getValue(verse)
}

/** Liste triee des positions d'espace + recherche binaire - remplace index_to_start_word/index_to_end_word (denses). */
class WordIndex(private val spacePositions: IntArray, private val textLength: Int) {
    fun startWord(i: Int): Int {
        var lo = 0; var hi = spacePositions.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (spacePositions[mid] <= i) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return if (ans == -1) 0 else spacePositions[ans] + 1
    }

    fun endWord(i: Int): Int {
        var lo = 0; var hi = spacePositions.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (spacePositions[mid] >= i) { ans = mid; hi = mid - 1 } else lo = mid + 1
        }
        return if (ans == -1) textLength - 1 else spacePositions[ans] - 1
    }
}

fun condense(s: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        sb.append(s[i])
        var j = i + 1
        while (j < s.length && s[j] == s[i]) j++
        i = j
    }
    return sb.toString()
}

class Finder(
    val vocab: String,
    private val cArray: IntArray,
    private val occPositions: Csr,
    private val sa: IntArray,
    val oneStringQuran: String,
    val oneStringUthmani: String,
    ayaBoundaries: AyaBoundaries,
    ayaBoundariesUthmani: AyaBoundaries,
    wordBoundaries: IntArray,
    val widthFactor: Double = 2.0,
) {
    val n: Int = oneStringQuran.length + 1

    private val vocabToIndex: Map<Char, Int> = vocab.withIndex().associate { (i, c) -> c to i }
    private val ayaIndex = AyaIndex(ayaBoundaries)
    private val ayaIndexUthmani = AyaIndex(ayaBoundariesUthmani)
    private val wordIndex = WordIndex(wordBoundaries, oneStringUthmani.length)

    /** occ(c, i) : compte cumule d'occurrences du caractere d'indice c dans L[0..i] inclus - miroir de la matrice
     * dense self.occ[c, i] de Python, y compris son indexation negative numpy : occ[c, -1] == compte total. */
    fun occAt(c: Int, i: Int): Int {
        val range = occPositions.range(c)
        if (i == -1) return range.last - range.first + 1
        var lo = range.first
        var hi = range.last + 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (occPositions.positions[mid] <= i) lo = mid + 1 else hi = mid
        }
        return lo - range.first
    }

    fun getIndices(p: String): IntArray = IntArray(p.length) { vocabToIndex.getValue(p[it]) }

    /** Miroir de backward_search_c (Correction.py) - recherche FM-index standard, seule la source de occ() change. */
    fun backwardSearch(pattern: IntArray): Pair<Int, Int> {
        val p = pattern.size
        var i = p - 1
        var c = pattern[i]
        var first: Int
        var last: Int
        if (c < cArray.size - 1) {
            first = cArray[c]; last = cArray[c + 1] - 1
        } else {
            first = cArray[c]; last = n - 1
        }
        while (first <= last && i >= 1) {
            c = pattern[i - 1]
            val newFirst = cArray[c] + occAt(c, first - 1)
            val newLast = cArray[c] + occAt(c, last) - 1
            first = newFirst
            last = newLast
            i -= 1
        }
        return if (last < first) -1 to -1 else first to last
    }

    fun getCandidatAyas(p: String, k: Int = 30, l0: Int = 10): List<Pair<Int, Int>> =
        getCandidatAyasWithVotes(p, k, l0).map { it.first }

    /**
     * Meme resultat que getCandidatAyas, mais avec le nombre de votes de
     * chaque candidat expose - reserve aux tests (FinderBehaviorGoldenTest) :
     * comme np.argsort(-counts) cote Python n'est pas stable sur les
     * egalites (limitation actee, voir plus bas), le seul contrat qu'on peut
     * verifier avec certitude est "meme ensemble + meme rang relatif entre
     * candidats non a egalite" - ce qui exige de connaitre les votes, pas
     * seulement l'ordre final.
     */
    fun getCandidatAyasWithVotes(p: String, k: Int = 30, l0: Int = 10): List<Pair<Pair<Int, Int>, Int>> {
        val condensed = condense(p)
        val indices = getIndices(condensed)
        val l = minOf(l0, indices.size)
        if (l == 0) return emptyList<Pair<Pair<Int, Int>, Int>>()
        val numWindows = indices.size / l // floor - miroir de sliding_window_view(...)[::l], le reste final est ignore

        val counts = LinkedHashMap<Pair<Int, Int>, Int>()
        for (w in 0 until numWindows) {
            val window = indices.copyOfRange(w * l, w * l + l)
            val (first, last) = backwardSearch(window)
            if (first == -1) {
                // Piege reel de Python, a reproduire tel quel : Mt_rows vient
                // de np.concatenate([np.arange(s, e+1) for s, e in zip(Firsts, Lasts)])
                // SANS filtrer les fenetres sans correspondance. Pour
                // s=e=-1, np.arange(-1, 0) = [-1], puis self.sa[-1]
                // (indexation negative numpy) = sa[n-1] - une "voix" parasite
                // pour l'aya contenant la toute derniere position du corpus,
                // PAS un skip. Sans ça, get_candidat_ayas/get_aya divergent
                // des que l'ASR produit une fenetre qui ne matche rien du
                // tout (trouve par le test dore comportemental).
                val aya = ayaIndex.ayaAt(sa[sa.size - 1])
                counts[aya] = (counts[aya] ?: 0) + 1
                continue
            }
            for (row in first..last) {
                val aya = ayaIndex.ayaAt(sa[row])
                counts[aya] = (counts[aya] ?: 0) + 1
            }
        }

        // np.unique trie lexicographiquement (sura,aya) croissant avant le
        // comptage ; argsort(-counts) reordonne par compte decroissant. Kotlin
        // reproduit ça avec deux tris STABLES successifs (sortedWith/
        // sortedByDescending sont garantis stables) - mais np.argsort(kind
        // par defaut = "quicksort") ne l'est PAS : verifie directement contre
        // numpy, l'ordre entre candidats a EGALITE de votes est un artefact
        // de son introsort interne (C, choix de pivot), jamais documente
        // comme contrat stable, pas reproductible sans reimplementer cet
        // algorithme. Limitation actee (option 1) : le tri stable ci-dessous
        // donne le meme ENSEMBLE de candidats et le meme rang pour toute
        // paire non a egalite ; seul l'ordre RELATIF entre candidats a
        // egalite de votes peut differer. Impact mesure sur le test dore
        // comportemental : 8033/8036 verifications strictement identiques,
        // et sur get_aya() (la seule sortie realmente consommee par
        // Corrector) seuls 3/392 cas divergent - uniquement quand une
        // egalite tombe pile a la frontiere de troncature `k`.
        val uniqueSorted = counts.keys.sortedWith(compareBy({ it.first }, { it.second }))
        val ranked = uniqueSorted.sortedByDescending { counts.getValue(it) }
        return ranked.take(minOf(k, ranked.size)).map { it to counts.getValue(it) }
    }

    /**
     * Best matching aya, its ratio, and the GAP to the runner-up.
     *
     * The gap is new: Correction.py::Finder.get_aya now returns a third value,
     * `ratios[best] - ratios[second_best]`, and Corrector.correct() gates its
     * re-anchoring search on `gap >= 0.2`. A large gap means one aya stands
     * clearly above the rest, i.e. the match is trustworthy enough to jump to.
     */
    data class AyaMatch(val aya: Pair<Int, Int>, val ratio: Double, val gap: Double)

    fun getAya(p: String, l0: Int = 10): AyaMatch {
        var l = l0
        var candidatsSize = 1 // mimique le dummy Python [0] (seule sa taille compte pour la condition de boucle)
        var candidats: List<Pair<Int, Int>> = emptyList()
        while (candidatsSize < 2 && l > 4) {
            candidats = getCandidatAyas(p, l0 = l)
            candidatsSize = candidats.size
            l -= 2
        }

        val sm = ModifiedMatcher(b = p)
        val ratios = candidats.map { cand ->
            val range = ayaIndex.rangeOf(cand)
            val text = oneStringQuran.substring(range.start, range.end)
            sm.setSeq1(text)
            sm.ratio()
        }
        val bestIdx = ratios.indices.maxByOrNull { ratios[it] } ?: error("get_aya: aucun candidat (l0=$l0 trop petit ?)")
        // Mirrors the Python exactly, quirks included:
        //   indices     = np.argsort(ratios)
        //   second_best = indices[-2] if len(ratios) > 2 else best_match - 1
        // With <= 2 candidates the runner-up is NOT the second-ranked element but
        // `best_match - 1` used as a raw index into `ratios` - and because Python
        // indexes negatively, best_match == 0 wraps round to the LAST element.
        // Reproduced rather than tidied: the gap it yields feeds the >= 0.2 gate.
        val secondIdx = if (ratios.size > 2) {
            ratios.indices.sortedBy { ratios[it] }[ratios.size - 2]
        } else {
            val raw = bestIdx - 1
            if (raw < 0) ratios.size + raw else raw
        }
        return AyaMatch(candidats[bestIdx], ratios[bestIdx], ratios[bestIdx] - ratios[secondIdx])
    }

    fun verseToCursor(verse: Pair<Int, Int>): Int {
        val r = ayaIndexUthmani.rangeOf(verse)
        return (r.start + r.end) / 2
    }

    /** (sura, aya) du verset contenant cette position uthmani - miroir de self.index_to_aya_uthmani[position], utilise par Corrector.get_aya() quand settled. */
    fun ayaAtUthmani(position: Int): Pair<Int, Int> = ayaIndexUthmani.ayaAt(position)

    /**
     * [start, end) du verset (sura, aya) dans `oneStringUthmani` - miroir
     * direct de `ayahBounds[sura][aya]` (Dani_app/web/app.js::buildQuranIndex).
     * Absent du Corrector Python lui-meme (calcule cote frontend JS
     * uniquement) - ajoute ici pour la pagination/le reveal Android (voir
     * app/mushaf/), qui a besoin des memes bornes sans reconstruire un index
     * separe : `ayaIndexUthmani` existe deja pour `verseToCursor`/`getFragment`.
     */
    fun ayaRangeUthmani(sura: Int, aya: Int): IdxRange = ayaIndexUthmani.rangeOf(sura to aya)

    // Nombre de versets par sourate, derive de ayaBoundariesUthmani (deja
    // charge pour ayaIndexUthmani) - pas une donnee separate a resynchroniser.
    // Lazy : seul le module app (liste des sourates, pagination) en a besoin,
    // jamais le chemin de correction chaud.
    private val ayahCounts: Map<Int, Int> by lazy {
        val counts = mutableMapOf<Int, Int>()
        for (i in ayaBoundariesUthmani.suras.indices) {
            val s = ayaBoundariesUthmani.suras[i]
            val a = ayaBoundariesUthmani.ayas[i]
            counts[s] = maxOf(counts.getOrDefault(s, 0), a)
        }
        counts
    }

    /** Nombre de versets de cette sourate - miroir de `surah.ayahs.length` (Dani_app/web/data/quran.json). */
    fun ayahCount(sura: Int): Int = ayahCounts.getValue(sura)

    fun getFragment(cursor: Int, width: Int, pushToVerse: Boolean = true): IdxRange {
        val m = oneStringUthmani.length
        val begin = cursor - (width * 0.7).toInt()
        val finish = cursor + (width * 0.5).toInt()
        return if (pushToVerse) {
            val startingVerse = ayaIndexUthmani.ayaAt(maxOf(begin, 0))
            val endingVerse = ayaIndexUthmani.ayaAt(minOf(finish, m - 1))
            val startIdx = ayaIndexUthmani.rangeOf(startingVerse).start
            val endIdx = ayaIndexUthmani.rangeOf(endingVerse).end - 1
            IdxRange(startIdx, endIdx)
        } else {
            val startIdx = wordIndex.startWord(maxOf(begin, 0))
            val endIdx = wordIndex.endWord(minOf(finish, m - 1))
            IdxRange(startIdx, endIdx)
        }
    }

    fun refinedUthmani(tilawa: String, cursor: Int, width: Int, refCursor: Int? = null, pushToVerse: Boolean = true): IdxRange {
        val effectiveRefCursor = refCursor ?: cursor
        val fragment = getFragment(cursor, width, pushToVerse)
        val start = fragment.start
        val end = fragment.end
        val localCursor = effectiveRefCursor - start
        val uthmaniText = oneStringUthmani.substring(start, end + 1)
        val phonetized = phonetize(uthmaniText)
        val track = phonetized.track

        val index = track.indices.minWithOrNull(compareBy({ abs(track[it] - localCursor) }, { -it })) ?: 0

        val sm = ModifiedMatcher(phonetized.phonetic, tilawa)
        val matchingBlocks = sm.getMatchingBlocks(nSpan = 0, match = MatchHint(index, false))
        if (matchingBlocks.isEmpty()) return IdxRange(start, end)

        val firstMatch = matchingBlocks.first().a
        val lastBlock = matchingBlocks.last()
        val lastMatch = lastBlock.a + lastBlock.sizeA - 1

        val wordStart = wordIndex.startWord(start + track[firstMatch])
        val wordEnd = wordIndex.endWord(start + track[firstMatch])
        val penetration = (wordEnd - start - track[firstMatch]).toDouble() / (wordEnd - wordStart)

        val firstUthmaniWord = if (penetration > 0.5) wordStart else wordEnd + 1
        val lastUthmaniWord = wordIndex.endWord(start + track[lastMatch])

        return IdxRange(firstUthmaniWord, lastUthmaniWord)
    }

    operator fun invoke(tilawa: String): String {
        val cursor = verseToCursor(getAya(tilawa).aya)
        val fragment = refinedUthmani(tilawa, cursor, (widthFactor * tilawa.length).toInt())
        return oneStringUthmani.substring(fragment.start, fragment.end + 1)
    }

    companion object {
        fun fromResources(basePath: String = "/finder"): Finder {
            fun stream(name: String) = requireNotNull(Finder::class.java.getResourceAsStream("$basePath/$name")) {
                "$basePath/$name introuvable sur le classpath - lancer scripts/build_android_assets.py"
            }
            fun text(name: String) = stream(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

            val vocab = text("vocab.txt")
            val c = readIntArray(stream("c.bin"))
            val occPositions = readCsr(stream("occ_positions.bin"))
            val sa = readIntArray(stream("sa.bin"))
            val oneStringQuran = text("one_string_quran.txt")
            val oneStringUthmani = text("one_string_uthmani.txt")
            val ayaBoundaries = readAyaBoundaries(stream("aya_boundaries.bin"))
            val ayaBoundariesUthmani = readAyaBoundaries(stream("aya_boundaries_uthmani.bin"))
            val wordBoundaries = readIntArray(stream("word_boundaries.bin"))

            return Finder(
                vocab = vocab,
                cArray = c,
                occPositions = occPositions,
                sa = sa,
                oneStringQuran = oneStringQuran,
                oneStringUthmani = oneStringUthmani,
                ayaBoundaries = ayaBoundaries,
                ayaBoundariesUthmani = ayaBoundariesUthmani,
                wordBoundaries = wordBoundaries,
            )
        }
    }
}
