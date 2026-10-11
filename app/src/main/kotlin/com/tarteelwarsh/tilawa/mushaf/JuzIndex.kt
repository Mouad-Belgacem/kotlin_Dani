package com.tarteelwarsh.tilawa.mushaf

import android.content.Context
import com.tarteelwarsh.tilawa.logic.correction.Finder
import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonArray
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonInt
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject

/**
 * Port fidele de buildJuzIndex/juzAtOffset (Dani_app/web/app.js) : une liste
 * triee (juz, offset) - offset = debut du juz' dans `oneStringUthmani`, via
 * `Finder.ayaRangeUthmani` - puis un scan lineaire pour "quel juz' contient
 * cet offset" (30 entrees, un scan est deja ce que fait app.js). Donnee
 * source (`juz_starts.json`) copiee verbatim depuis
 * `Dani_app/web/data/juz-starts.json`.
 */
class JuzIndex(private val sortedStarts: List<Pair<Int, Int>>) { // (juz, offset), tries par offset croissant

    fun juzAt(offset: Int): Int {
        var juz = 1
        for ((j, off) in sortedStarts) {
            if (off > offset) break
            juz = j
        }
        return juz
    }

    companion object {
        fun fromAssets(context: Context, finder: Finder, assetName: String = "juz_starts.json"): JuzIndex {
            val text = context.assets.open(assetName).use { it.bufferedReader(Charsets.UTF_8).readText() }
            val entries = JsonParser.parse(text).asJsonArray().map { it.asJsonObject() }
            val starts = entries
                .map { e ->
                    val juz = e.getValue("juz").asJsonInt()
                    val sura = e.getValue("sura").asJsonInt()
                    val aya = e.getValue("aya").asJsonInt()
                    juz to finder.ayaRangeUthmani(sura, aya).start
                }
                .sortedBy { it.second }
            return JuzIndex(starts)
        }
    }
}
