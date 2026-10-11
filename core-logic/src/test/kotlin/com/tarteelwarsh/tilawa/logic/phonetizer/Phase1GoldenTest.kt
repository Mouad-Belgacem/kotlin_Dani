package com.tarteelwarsh.tilawa.logic.phonetizer

import org.junit.jupiter.api.Test
import kotlin.test.fail

/**
 * Test dore : compare ph1T() (port Kotlin) a la sortie REELLE de
 * track_phase1.ph1T() sur les 6330 versets de moshaf.json, generee par
 * scripts/generate_phonetizer_goldens.py (jamais retouchee a la main).
 *
 * Valide uniquement la phase 1 (regles T1/T2/T3), independamment des
 * handlers bab_alhuruf.py - voir PhonetizerGoldenTest pour phonetize() en
 * entier (phase 1 + phase 2).
 */
class Phase1GoldenTest {

    @Test
    fun `ph1T matches the Python reference on every verse of the Quran`() {
        val fixtures = loadFixtures("/goldens/phonetizer/quran_phase1_goldens.json")

        val mismatches = mutableListOf<String>()
        for (fixture in fixtures) {
            for (variant in listOf(false, true)) {
                val expected = fixture.variant(variant)
                val wrapped = "$" + fixture.input + "$"
                val actual = ph1T(wrapped, attachLawasiq = variant)

                if (actual.transformed != expected.transformed) {
                    mismatches += "[${fixture.sura}:${fixture.aya} lawasiq=$variant] transformed:\n" +
                        "  attendu : ${expected.transformed}\n" +
                        "  obtenu  : ${actual.transformed}"
                } else if (actual.track != expected.track) {
                    mismatches += "[${fixture.sura}:${fixture.aya} lawasiq=$variant] track:\n" +
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

    private data class Phase1FixtureVariant(val transformed: String, val track: List<Int>)

    private data class Phase1Fixture(
        val sura: Int,
        val aya: Int,
        val input: String,
        val noLawasiq: Phase1FixtureVariant,
        val withLawasiq: Phase1FixtureVariant,
    ) {
        fun variant(attachLawasiq: Boolean) = if (attachLawasiq) withLawasiq else noLawasiq
    }

    private fun loadFixtures(resourcePath: String): List<Phase1Fixture> {
        val stream = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
            "Fixture introuvable: $resourcePath - lancer scripts/generate_phonetizer_goldens.py"
        }
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JsonParser.parse(text).asJsonArray()
        return root.map { entry ->
            val obj = entry.asJsonObject()
            fun variantOf(key: String): Phase1FixtureVariant {
                val v = obj.getValue(key).asJsonObject()
                val track = v.getValue("track").asJsonArray().map { it.asJsonInt() }
                return Phase1FixtureVariant(v.getValue("transformed").asJsonString(), track)
            }
            Phase1Fixture(
                sura = obj.getValue("sura").asJsonInt(),
                aya = obj.getValue("aya").asJsonInt(),
                input = obj.getValue("input").asJsonString(),
                noLawasiq = variantOf("noLawasiq"),
                withLawasiq = variantOf("withLawasiq"),
            )
        }
    }
}
