package com.tarteelwarsh.tilawa.mushaf

import android.content.Context
import com.tarteelwarsh.tilawa.logic.correction.Finder
import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonArray
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonBoolean
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonString

/**
 * Metadonnees d'une sourate pour la liste (ecran "sura_selection") - nom
 * (sura_names.json, deja porte), nombre de versets (`Finder.ayahCount`, deja
 * dans core-logic). Le statut mecquois/medinois N'EST PAS une donnee
 * Dani_app (absent de quran.json ET de warshData_v10.json - verifie) : c'est
 * une classification standard, largement admise (identique dans la quasi-
 * totalite des editions imprimees, dont l'index du Mushaf de Medine),
 * ajoutee ici comme pure metadonnee decorative pour la carte de sourate du
 * mockup Stitch (`sura_selection/code.html`) - absente de la sidebar reelle
 * de Dani_app (`app.js::renderSurahList`, qui n'affiche que numero + nom).
 */
data class SuraMeta(val number: Int, val name: String, val ayahCount: Int, val meccan: Boolean)

object SuraMetaLoader {
    fun load(context: Context, finder: Finder): List<SuraMeta> {
        val names = context.assets.open("sura_names.json").use { stream ->
            JsonParser.parse(stream.bufferedReader(Charsets.UTF_8).readText()).asJsonArray().map { it.asJsonString() }
        }
        val meccan = context.assets.open("sura_meccan.json").use { stream ->
            JsonParser.parse(stream.bufferedReader(Charsets.UTF_8).readText()).asJsonArray().map { it.asJsonBoolean() }
        }
        require(names.size == 114 && meccan.size == 114) { "sura_names.json/sura_meccan.json doivent contenir 114 entrees" }
        return (1..114).map { n ->
            SuraMeta(number = n, name = names[n - 1], ayahCount = finder.ayahCount(n), meccan = meccan[n - 1])
        }
    }
}
