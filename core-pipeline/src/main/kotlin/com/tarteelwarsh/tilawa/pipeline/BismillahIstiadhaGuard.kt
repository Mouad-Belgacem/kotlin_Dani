package com.tarteelwarsh.tilawa.pipeline

import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonString

/**
 * Port fidele du garde-fou de `process_chunk()` (Dani_app/api/pipeline.py,
 * juste avant le retour normal) : tant que le curseur n'est pas `settled`,
 * une correction dont le texte uthmani choisi est un SOUS-TEXTE de la
 * Basmala ou de l'Istiadha (formules recitees en preambule, hors du texte
 * cible) est ignoree (score remis a 0, pas de resultat renvoye) - evite de
 * faire "accrocher" le curseur sur ce preambule avant qu'il ait vraiment
 * localise la recitation. Les deux chaines de reference sont chargees
 * depuis bismillah_istiadha.json (extraites mecaniquement du fichier source
 * Python, jamais retranscrites a la main - risque de transcription trop
 * eleve sur du texte arabe avec diacritiques).
 */
private data class Reference(val bismillah: String, val istiadha: String)

private val reference: Reference by lazy {
    val stream = requireNotNull(object {}.javaClass.getResourceAsStream("/bismillah_istiadha.json")) {
        "/bismillah_istiadha.json introuvable sur le classpath"
    }
    val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    val obj = JsonParser.parse(text).asJsonObject()
    Reference(obj.getValue("s1").asJsonString(), obj.getValue("s2").asJsonString())
}

fun isBismillahOrIstiadhaPrefix(uthmaniText: String): Boolean {
    if (uthmaniText.isEmpty()) return false
    return reference.bismillah.contains(uthmaniText) || reference.istiadha.contains(uthmaniText)
}
