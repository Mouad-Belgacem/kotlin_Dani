package com.tarteelwarsh.tilawa.logic.phonetizer

import org.junit.jupiter.api.Test
import kotlin.test.fail

/**
 * Test dore : compare phonetize() (port Kotlin, phase 1 + phase 2 assemblees)
 * a la sortie REELLE de Correction.phonetize() sur les 6330 versets de
 * moshaf.json, generee par scripts/generate_phonetizer_goldens.py (jamais
 * retouchee a la main). C'est le contrat final utilise par Finder/Corrector -
 * le critere de "termine" pour le phonetiseur.
 */
class PhonetizerGoldenTest {

    @Test
    fun `phonetize matches the Python reference on every verse of the Quran`() {
        val fixtures = loadFixtures("/goldens/phonetizer/quran_phonetizer_goldens.json")

        val mismatches = mutableListOf<String>()
        for (fixture in fixtures) {
            for (attachLawasiq in listOf(false, true)) {
                val expected = if (attachLawasiq) fixture.withLawasiq else fixture.noLawasiq
                val actual = phonetize(fixture.input, attachLawasiq = attachLawasiq)

                if (actual.phonetic != expected.phonetic) {
                    mismatches += "[${fixture.sura}:${fixture.aya} lawasiq=$attachLawasiq] phonetic:\n" +
                        "  attendu : ${expected.phonetic}\n" +
                        "  obtenu  : ${actual.phonetic}"
                } else if (actual.track != expected.track) {
                    mismatches += "[${fixture.sura}:${fixture.aya} lawasiq=$attachLawasiq] track:\n" +
                        "  attendu : ${expected.track}\n" +
                        "  obtenu  : ${actual.track}"
                }
                if (mismatches.size >= 25) break
            }
            if (mismatches.size >= 25) break
        }

        if (mismatches.isNotEmpty()) {
            fail(
                "${mismatches.size} divergence(s) (limite a 25 affichees) sur ${fixtures.size} versets :\n\n" +
                    mismatches.joinToString("\n\n")
            )
        }
    }

    private data class FixtureVariant(val phonetic: String, val track: List<Int>)

    private data class Fixture(
        val sura: Int,
        val aya: Int,
        val input: String,
        val noLawasiq: FixtureVariant,
        val withLawasiq: FixtureVariant,
    )

    private fun loadFixtures(resourcePath: String): List<Fixture> {
        val stream = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
            "Fixture introuvable: $resourcePath - lancer scripts/generate_phonetizer_goldens.py"
        }
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JsonParser.parse(text).asJsonArray()
        return root.map { entry ->
            val obj = entry.asJsonObject()
            fun variantOf(key: String): FixtureVariant {
                val v = obj.getValue(key).asJsonObject()
                val track = v.getValue("track").asJsonArray().map { it.asJsonInt() }
                return FixtureVariant(v.getValue("phonetic").asJsonString(), track)
            }
            Fixture(
                sura = obj.getValue("sura").asJsonInt(),
                aya = obj.getValue("aya").asJsonInt(),
                input = obj.getValue("input").asJsonString(),
                noLawasiq = variantOf("noLawasiq"),
                withLawasiq = variantOf("withLawasiq"),
            )
        }
    }
}
