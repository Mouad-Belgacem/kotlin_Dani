package com.tarteelwarsh.tilawa.pipeline

import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonBoolean
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixtures generees par scripts/generate_bismillah_istiadha_goldens.py -
 * cas nominal (sous-textes de la Basmala/Istiadha) ET cas limites reels de
 * tajwid extraits mecaniquement du VRAI Finder (jamais retapes a la main) :
 * - At-Tawbah (9:1) n'a PAS de Basmala avant son premier verset - le garde-fou
 *   ne doit PAS se declencher sur son texte reel (ni sur ses premiers mots
 *   seuls, le cas realiste ou l'ASR ne transcrit qu'un fragment).
 * - Al-Fatiha (1:1) : dans la convention de decoupage utilisee par ce
 *   Finder, le premier verset concatene la Basmala ET la suite ("Al-hamdu
 *   ...") - le texte complet de 1:1 n'est donc PAS un sous-texte litteral de
 *   la seule Basmala (verifie par le vrai `in` Python, pas suppose).
 * - Quasi-collisions (inversion, caractere supprime/ajoute, diacritiques
 *   retires) : verifient que le garde-fou reste strict, sans tolerance
 *   implicite sur des textes proches mais pas litteralement identiques.
 */
class BismillahIstiadhaGuardTest {
    @Test
    fun `matches the real process_chunk guard condition on nominal and edge cases`() {
        val text = javaClass.getResourceAsStream("/bismillah_istiadha_cases.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val cases = JsonParser.parse(text).asJsonObject()
        for ((label, caseValue) in cases) {
            val case = caseValue.asJsonObject()
            val input = case.getValue("text").asJsonString()
            val expected = case.getValue("expected").asJsonBoolean()
            // Reproduit la condition reelle de process_chunk() : (uth_text in
            // BISMILLAH or uth_text in ISTIADHA) and uth_text - le "and
            // uth_text" est ce qui exclut la chaine vide, deja gere par le
            // isEmpty() de isBismillahOrIstiadhaPrefix elle-meme.
            val actual = input.isNotEmpty() && isBismillahOrIstiadhaPrefix(input)
            assertEquals("cas \"$label\" (input=\"$input\")", expected, actual)
        }
    }
}
