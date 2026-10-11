package com.tarteelwarsh.tilawa.mushaf

import com.tarteelwarsh.tilawa.logic.correction.Finder
import com.tarteelwarsh.tilawa.logic.correction.IdxRange

/**
 * Port fidele de buildBasmalaIndex/isBasmalaOffset (Dani_app/web/app.js) : la
 * basmala d'ouverture de sourate n'est PAS un verset a part dans les
 * donnees - c'est un PREFIXE du texte de l'ayah 1 de chaque sourate (sauf la
 * sourate 9, At-Tawbah, qui n'en a pas - exactement le cas explicitement
 * demande a couvrir). Derivee du texte lui-meme (plus long prefixe commun
 * entre l'ayah 1 de la sourate 1 et celui de la sourate 2), PAS d'une chaine
 * figee : ne peut donc jamais diverger de l'encodage exact de
 * `Finder.oneStringUthmani`, et couvre generiquement les 113 sourates
 * concernees sans cas particulier code en dur.
 */
class BasmalaIndex private constructor(
    private val bounds: Map<Int, Bounds>,
    private val sortedRanges: List<IdxRange>, // pour isBasmalaOffset (recherche binaire)
) {
    /** [start, end) de la basmala dans oneStringUthmani ; restStart = ou commence le texte propre de l'ayah 1 (apres la basmala + ses espaces separateurs). */
    data class Bounds(val start: Int, val end: Int, val restStart: Int)

    fun boundsFor(sura: Int): Bounds? = bounds[sura]

    fun isBasmalaOffset(offset: Int): Boolean {
        var lo = 0
        var hi = sortedRanges.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = sortedRanges[mid]
            when {
                offset < r.start -> hi = mid - 1
                offset >= r.end -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    companion object {
        fun from(finder: Finder): BasmalaIndex {
            val s = finder.oneStringUthmani
            val r1 = finder.ayaRangeUthmani(1, 1)
            val r2 = finder.ayaRangeUthmani(2, 1)
            val a = s.substring(r1.start, r1.end)
            val b = s.substring(r2.start, r2.end)
            var n = 0
            while (n < a.length && n < b.length && a[n] == b[n]) n++
            val basmala = a.substring(0, n).trimEnd()

            val bounds = mutableMapOf<Int, Bounds>()
            val ranges = mutableListOf<IdxRange>()
            for (sura in 1..114) {
                val firstRange = finder.ayaRangeUthmani(sura, 1)
                val first = s.substring(firstRange.start, firstRange.end)
                if (!first.startsWith("$basmala ")) continue // sourate 9 : pas de basmala d'ouverture
                var restLocal = basmala.length
                while (restLocal < first.length && first[restLocal] == ' ') restLocal++
                val start = firstRange.start
                val bb = Bounds(start = start, end = start + basmala.length, restStart = start + restLocal)
                bounds[sura] = bb
                ranges.add(IdxRange(bb.start, bb.end))
            }
            return BasmalaIndex(bounds, ranges.sortedBy { it.start })
        }
    }
}
