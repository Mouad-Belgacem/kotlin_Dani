package com.tarteelwarsh.tilawa.logic.correction

import kotlin.math.abs
import kotlin.math.sqrt

// Port fidele de Dani_app/tilawa_correction/Correction.py::ModifiedMatcher,
// une variante de difflib.SequenceMatcher. La classe Python herite de
// SequenceMatcher mais redefinit entierement __chain_b, find_longest_match et
// get_matching_blocks ; comme isjunk est toujours None a la construction dans
// tout Dani_app, toute la logique "junk"/autojunk de la classe de base
// n'entre jamais en jeu et n'est pas portee ici - ce port reimplemente
// directement le comportement effectivement observable, pas la classe de
// base entiere.

data class Match3(val a: Int, val b: Int, val size: Int)
data class MatchBlock(val a: Int, val b: Int, val sizeA: Int, val sizeB: Int)
data class MatchHint(val position: Int, val isEnd: Boolean)

/** Condense les repetitions consecutives - miroir de map_from_condense(). Retourne le texte condense + un tableau de correspondance (une entree par caractere condense, pointant vers le debut de son run dans le texte original, plus une entree finale sentinelle = longueur du texte original). */
fun mapFromCondense(script: String): Pair<String, List<Int>> {
    val compressed = StringBuilder()
    val correspondence = ArrayList<Int>()
    var i = 0
    while (i < script.length) {
        compressed.append(script[i])
        correspondence.add(i)
        var j = i + 1
        while (j < script.length && script[j] == script[i]) j++
        i = j
    }
    correspondence.add(script.length)
    return compressed.toString() to correspondence
}

class ModifiedMatcher(
    a: String = "",
    b: String = "",
    private val minD: Int = 2,
    private val minLength: Int = 4,
) {
    var a: String = a
        private set
    var b: String = b
        private set

    private var b2j: Map<Char, List<Int>> = emptyMap()
    private var cachedMatchingBlocks: List<MatchBlock>? = null

    init {
        chainB()
    }

    fun setSeq1(newA: String) {
        a = newA
        cachedMatchingBlocks = null
    }

    fun setSeq2(newB: String) {
        b = newB
        cachedMatchingBlocks = null
        chainB()
    }

    /** Miroir de _SequenceMatcher__chain_b : b2j indexe la version CONDENSEE de b, jamais reconstruit ailleurs (en particulier pas dans getMatchingBlocks - Python ne le fait pas non plus, self.b y est reassigne a une valeur qui EST deja la version condensee sur laquelle b2j a ete construit). */
    private fun chainB() {
        val condensedB = mapFromCondense(b).first
        val map = LinkedHashMap<Char, MutableList<Int>>()
        for ((i, elt) in condensedB.withIndex()) {
            map.getOrPut(elt) { mutableListOf() }.add(i)
        }
        b2j = map
    }

    fun findLongestMatch(
        alo: Int = 0,
        ahi: Int = a.length,
        blo: Int = 0,
        bhi: Int = b.length,
        match: MatchHint = MatchHint(-1, true),
    ): Match3 {
        val effectiveMatch = if (match.position == -1) MatchHint(a.length - 1, true) else match

        var bestrealsize = 0
        var bestis = mutableListOf(alo)
        var bestjs = mutableListOf(blo)
        var bestrealsizes = mutableListOf(0)

        var j2len: Map<Int, Int> = emptyMap()
        for (i in alo until ahi) {
            val newj2len = HashMap<Int, Int>()
            val candidates = b2j[a[i]] ?: emptyList()
            for (j in candidates) {
                if (j < blo) continue
                if (j >= bhi) break
                val length = (j2len[j - 1] ?: 0) + 1
                newj2len[j] = length
                if (length > bestrealsize) {
                    bestrealsize = length
                    bestis = mutableListOf(i - length + 1)
                    bestjs = mutableListOf(j - length + 1)
                    bestrealsizes = mutableListOf(length)
                } else if (length == bestrealsize) {
                    bestis.add(i - length + 1)
                    bestjs.add(j - length + 1)
                    bestrealsizes.add(length)
                }
            }
            j2len = newj2len
        }

        val index = if (effectiveMatch.isEnd) {
            bestis.indices.minByOrNull { abs(bestis[it] + bestrealsizes[it] - effectiveMatch.position) }!!
        } else {
            bestis.indices.minByOrNull { abs(bestis[it] - effectiveMatch.position) }!!
        }

        return Match3(bestis[index], bestjs[index], bestrealsizes[index])
    }

    fun getMatchingBlocks(nSpan: Int = 3, match: MatchHint = MatchHint(-1, true)): List<MatchBlock> {
        cachedMatchingBlocks?.let { return it }

        val aNc = a
        val bNc = b
        val (condensedA, mapA) = mapFromCondense(a)
        val (condensedB, mapB) = mapFromCondense(b)
        a = condensedA
        b = condensedB
        // Pas de rechainB() ici : b2j a deja ete construit (a la derniere
        // setSeq2/construction) a partir de la version condensee de b, qui
        // est exactement `condensedB` ci-dessus - fidele a l'original Python.
        val la = a.length
        val lb = b.length

        data class QueueItem(val alo: Int, val ahi: Int, val blo: Int, val bhi: Int, val match: MatchHint)

        val queue = ArrayDeque<QueueItem>()
        queue.addLast(QueueItem(0, la, 0, lb, match))
        val rawBlocks = ArrayList<Match3>()
        while (queue.isNotEmpty()) {
            val item = queue.removeLast() // pile LIFO, comme queue.pop() de Python (list utilise comme pile)
            val x = findLongestMatch(item.alo, item.ahi, item.blo, item.bhi, item.match)
            if (x.size != 0) {
                rawBlocks.add(x)
                if (item.alo < x.a && item.blo < x.b) {
                    queue.addLast(QueueItem(item.alo, x.a, item.blo, x.b, MatchHint(x.a, true)))
                }
                if (x.a + x.size < item.ahi && x.b + x.size < item.bhi) {
                    queue.addLast(QueueItem(x.a + x.size, item.ahi, x.b + x.size, item.bhi, MatchHint(x.a + x.size, false)))
                }
            }
        }
        rawBlocks.sortWith(compareBy({ it.a }, { it.b }, { it.size }))

        var i1 = 0
        var j1 = 0
        var k1 = 0
        val nonAdjacent = ArrayList<Match3>()
        for (block in rawBlocks) {
            val (i2, j2, k2) = block
            if (i1 + k1 == i2 && j1 + k1 == j2) {
                k1 += k2
            } else {
                if (k1 != 0) nonAdjacent.add(Match3(i1, j1, k1))
                i1 = i2
                j1 = j2
                k1 = k2
            }
        }
        if (k1 != 0) nonAdjacent.add(Match3(i1, j1, k1))

        var blocks: List<Match3> = nonAdjacent
        val filtered = BooleanArray(blocks.size) { blocks[it].size >= minLength }
        repeat(nSpan) {
            for (i in 0 until blocks.size - 1) {
                if (filtered[i] != filtered[i + 1]) {
                    val next = blocks[i + 1]
                    val cur = blocks[i]
                    val d = next.a - (cur.a + cur.size)
                    if (d <= minD) {
                        filtered[i] = true
                        filtered[i + 1] = true
                    }
                }
            }
        }
        blocks = blocks.filterIndexed { idx, _ -> filtered[idx] }

        val result = blocks.map { (i, j, k) ->
            MatchBlock(mapA[i], mapB[j], mapA[i + k] - mapA[i], mapB[j + k] - mapB[j])
        }

        a = aNc
        b = bNc
        cachedMatchingBlocks = result
        return result
    }

    /**
     * Correction.py::ModifiedMatcher.ratio - signature kept to `boarder_penalty`
     * only.
     *
     * A local `tailNotYetRecited` option used to live here, excluding the window
     * tail past the last match so a short `b` could not score structurally
     * negative. It has been removed: the current Python has no such parameter,
     * and the problem it worked around is now addressed upstream - the corrector
     * starts at score 2.0, so the search window `w` opens far narrower and there
     * is much less unmatched tail to be charged for.
     */
    fun ratio(boarderPenalty: Boolean = false): Double {
        val blocks = getMatchingBlocks()
        val matchesA = blocks.sumOf { it.sizeA }
        val matchesB = blocks.sumOf { it.sizeB }
        val mismatchesA: Int
        val mismatchesB: Int
        if (boarderPenalty) {
            mismatchesA = a.length - matchesA
            mismatchesB = b.length - matchesB
        } else {
            var ma = 0
            var mb = 0
            for (i in 0 until blocks.size - 1) {
                ma += blocks[i + 1].a - blocks[i].a - blocks[i].sizeA
                mb += blocks[i + 1].b - blocks[i].b - blocks[i].sizeB
            }
            mismatchesA = ma
            mismatchesB = mb
        }
        // b empty -> sqrt(0) -> 0.0/0.0 = NaN, which propagates into Corrector.score,
        // the trust meter and the persisted history JSON (where NaN is not even valid
        // JSON). Nothing matches an empty transcription, so 0.0 is the honest answer.
        if (b.isEmpty()) return 0.0
        return (matchesA + matchesB - mismatchesA - mismatchesB) / 16.0 / sqrt(b.length.toDouble())
    }
}
