package com.tarteelwarsh.tilawa.mushaf

import android.content.Context
import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonArray
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonInt
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject

/** Un segment continu d'une sourate sur une page - miroir de `pages[i] = [{sura, from, to}, ...]` (Dani_app/web/app.js). */
data class PageSegment(val sura: Int, val from: Int, val to: Int)

/**
 * Port fidele de la pagination du vrai Mushaf de Medine (604 pages, riwaya
 * Warsh) telle qu'utilisee par Dani_app - PAS reinventee a partir des
 * captures d'ecran : `pages` est charge tel quel depuis
 * `Dani_app/web/data/pages.json` (copie verbatim dans
 * `app/src/main/assets/mushaf_pages.json`, genere par
 * `Dani_app/scripts/build_pages_from_kfgqpc.py` a partir du jeu de donnees
 * officiel KFGQPC - meme fichier, aucune regeneration necessaire).
 *
 * Numerotation (sura, aya) verifiee IDENTIQUE a celle de `core-logic.Finder`
 * (meme 6214 versets au total, memes textes caractere-pres sur plusieurs
 * echantillons - 1:1, 2:255, 9:129, 114:6, 18:10 - voir app/README.md) :
 * aucune reconciliation d'index entre les deux sources n'est necessaire, a
 * la difference de Dani_app/web/app.js qui doit se proteger d'une derive
 * entre son propre quran.json et le one_string_uthmani du backend (voir
 * le controle de parite dans record.js::renderResult, non porte ici -
 * structurellement impossible dans ce port ou les deux viennent du meme Finder).
 */
class PageIndex(val pages: List<List<PageSegment>>) {
    // pageOf[sura][aya] = numero de page (1-indexe) - miroir de buildPageIndex() (app.js).
    private val pageOfSuraAya: Map<Int, Map<Int, Int>>

    init {
        val builder = HashMap<Int, HashMap<Int, Int>>()
        pages.forEachIndexed { i, segments ->
            segments.forEach { seg ->
                val forSura = builder.getOrPut(seg.sura) { HashMap() }
                for (a in seg.from..seg.to) forSura[a] = i + 1
            }
        }
        pageOfSuraAya = builder
    }

    fun pageOf(sura: Int, aya: Int): Int = pageOfSuraAya.getValue(sura).getValue(aya)

    fun segments(pageNumber: Int): List<PageSegment> = pages[pageNumber - 1]

    val pageCount: Int get() = pages.size

    companion object {
        fun fromAssets(context: Context, assetName: String = "mushaf_pages.json"): PageIndex {
            val text = context.assets.open(assetName).use { it.bufferedReader(Charsets.UTF_8).readText() }
            val root = JsonParser.parse(text).asJsonObject()
            val pages = root.getValue("pages").asJsonArray().map { pageValue ->
                pageValue.asJsonArray().map { segValue ->
                    val obj = segValue.asJsonObject()
                    PageSegment(
                        sura = obj.getValue("sura").asJsonInt(),
                        from = obj.getValue("from").asJsonInt(),
                        to = obj.getValue("to").asJsonInt(),
                    )
                }
            }
            return PageIndex(pages)
        }
    }
}
