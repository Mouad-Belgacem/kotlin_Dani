package com.tarteelwarsh.tilawa.pipeline

import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonArray
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonInt
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject

/**
 * Port fidele de `add_harakat()` (Dani_app/api/pipeline.py) - insere une
 * harakat par defaut avant certaines lettres "nues" du flux `tilawa` produit
 * par le decodeur CTC (mode `help_tilawa`, `offset==2` a la creation de la
 * session cote Python). Regles chargees depuis harakat_rules.json (extrait
 * mecaniquement du corps de la fonction Python via ses points de code -
 * jamais retranscrit a la main, risque de transcription trop eleve sur des
 * marques diacritiques arabes).
 */
private data class HarakatRule(val trigger: Char, val avoid: Set<Char>, val insert: Char)

private val rules: List<HarakatRule> by lazy {
    val stream = requireNotNull(object {}.javaClass.getResourceAsStream("/harakat_rules.json")) {
        "/harakat_rules.json introuvable sur le classpath"
    }
    val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    JsonParser.parse(text).asJsonArray().map { entry ->
        val obj = entry.asJsonObject()
        HarakatRule(
            trigger = obj.getValue("triggerCp").asJsonInt().toChar(),
            avoid = obj.getValue("avoidCp").asJsonArray().map { it.asJsonInt().toChar() }.toSet(),
            insert = obj.getValue("insertCp").asJsonInt().toChar(),
        )
    }
}

fun addHarakat(tilawa: String): String {
    if (tilawa.isEmpty()) return ""
    val result = StringBuilder()
    result.append(tilawa[0])
    for (i in 1 until tilawa.length) {
        val c = tilawa[i]
        val prev = tilawa[i - 1]
        val rule = rules.firstOrNull { it.trigger == c }
        if (rule != null && prev !in rule.avoid) {
            result.append(rule.insert)
        }
        result.append(c)
    }
    return result.toString()
}
