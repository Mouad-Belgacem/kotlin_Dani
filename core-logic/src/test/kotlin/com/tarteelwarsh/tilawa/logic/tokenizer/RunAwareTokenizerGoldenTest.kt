package com.tarteelwarsh.tilawa.logic.tokenizer

import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonArray
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonInt
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonString
import org.junit.jupiter.api.Test
import kotlin.test.fail

/**
 * Test dore : compare RunAwareTokenizer (port Kotlin) a la sortie REELLE de
 * scripts/run_aware_tokenizer.py, generee par
 * scripts/generate_tokenizer_goldens.py sur les 6330 sorties phonetiques du
 * Coran (deja validees par PhonetizerGoldenTest) + les cas limites du script
 * d'origine (6/8 alifs consecutifs).
 */
class RunAwareTokenizerGoldenTest {

    @Test
    fun `encode and decode match the Python reference on every fixture`() {
        val tokenizer = RunAwareTokenizer.fromResource()
        val fixtures = loadFixtures("/goldens/tokenizer/tokenizer_goldens.json")

        val mismatches = mutableListOf<String>()
        for (fixture in fixtures) {
            val ids = tokenizer.encode(fixture.text)
            if (ids != fixture.ids) {
                mismatches += "text=${fixture.text}\n  ids attendus : ${fixture.ids}\n  ids obtenus  : $ids"
            }
            val decoded = tokenizer.decode(ids)
            if (decoded != fixture.decoded) {
                mismatches += "text=${fixture.text}\n  decode attendu : ${fixture.decoded}\n  decode obtenu  : $decoded"
            }
            if (mismatches.size >= 25) break
        }

        if (mismatches.isNotEmpty()) {
            fail(
                "${mismatches.size} divergence(s) (limite a 25 affichees) sur ${fixtures.size} textes :\n\n" +
                    mismatches.joinToString("\n\n")
            )
        }
    }

    private data class Fixture(val text: String, val ids: List<Int>, val decoded: String)

    private fun loadFixtures(resourcePath: String): List<Fixture> {
        val stream = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
            "Fixture introuvable: $resourcePath - lancer scripts/generate_tokenizer_goldens.py"
        }
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JsonParser.parse(text).asJsonArray()
        return root.map { entry ->
            val obj = entry.asJsonObject()
            Fixture(
                text = obj.getValue("text").asJsonString(),
                ids = obj.getValue("ids").asJsonArray().map { it.asJsonInt() },
                decoded = obj.getValue("decoded").asJsonString(),
            )
        }
    }
}
