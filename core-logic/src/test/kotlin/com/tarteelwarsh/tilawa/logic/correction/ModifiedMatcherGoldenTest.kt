package com.tarteelwarsh.tilawa.logic.correction

import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonArray
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonInt
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonString
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Test dore : compare ModifiedMatcher (port Kotlin) a la sortie REELLE de
 * Correction.ModifiedMatcher sur 700+ paires (a, b) construites a partir de
 * sorties phonetiques reelles + mutations synthetiques simulant des erreurs
 * ASR (substitutions, troncatures, changements de longueur de repetition),
 * generees par scripts/generate_matcher_goldens.py.
 */
class ModifiedMatcherGoldenTest {

    @Test
    fun `get_matching_blocks and ratio match the Python reference`() {
        val fixtures = loadFixtures("/goldens/matcher/matcher_goldens.json")
        val mismatches = mutableListOf<String>()

        for (fixture in fixtures) {
            for (case in fixture.cases) {
                val sm = ModifiedMatcher(a = fixture.a, b = fixture.b)
                val match = if (case.matchIndex != null) MatchHint(case.matchIndex, false) else MatchHint(-1, true)
                val nSpan = if (case.label == "n_span0_start_mid") 0 else 3
                val blocks = sm.getMatchingBlocks(nSpan = nSpan, match = match)
                val actualBlocks = blocks.map { listOf(it.a, it.b, it.sizeA, it.sizeB) }

                if (actualBlocks != case.matchingBlocks) {
                    mismatches += "[${fixture.sura}:${fixture.aya} ${fixture.kind}/${case.label}] matchingBlocks:\n" +
                        "  a=${fixture.a}\n  b=${fixture.b}\n" +
                        "  attendu : ${case.matchingBlocks}\n" +
                        "  obtenu  : $actualBlocks"
                }

                if (case.ratio != null) {
                    val actualRatio = sm.ratio()
                    if (!approxEquals(actualRatio, case.ratio)) {
                        mismatches += "[${fixture.sura}:${fixture.aya} ${fixture.kind}/${case.label}] ratio: attendu=${case.ratio} obtenu=$actualRatio"
                    }
                }
                if (case.ratioBoarderPenalty != null) {
                    val actualRatioBp = sm.ratio(boarderPenalty = true)
                    if (!approxEquals(actualRatioBp, case.ratioBoarderPenalty)) {
                        mismatches += "[${fixture.sura}:${fixture.aya} ${fixture.kind}/${case.label}] ratioBoarderPenalty: attendu=${case.ratioBoarderPenalty} obtenu=$actualRatioBp"
                    }
                }
                if (mismatches.size >= 25) break
            }
            if (mismatches.size >= 25) break
        }

        if (mismatches.isNotEmpty()) {
            fail(
                "${mismatches.size} divergence(s) (limite a 25 affichees) sur ${fixtures.size} cas :\n\n" +
                    mismatches.joinToString("\n\n")
            )
        }
    }

    private fun approxEquals(x: Double, y: Double, eps: Double = 1e-9) = kotlin.math.abs(x - y) < eps

    private data class CaseFixture(
        val label: String,
        val matchIndex: Int?,
        val matchingBlocks: List<List<Int>>,
        val ratio: Double?,
        val ratioBoarderPenalty: Double?,
    )

    private data class MatcherFixture(val sura: Int, val aya: Int, val kind: String, val a: String, val b: String, val cases: List<CaseFixture>)

    private fun loadFixtures(resourcePath: String): List<MatcherFixture> {
        val stream = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
            "Fixture introuvable: $resourcePath - lancer scripts/generate_matcher_goldens.py"
        }
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JsonParser.parse(text).asJsonArray()
        return root.map { entry ->
            val obj = entry.asJsonObject()
            val cases = obj.getValue("cases").asJsonArray().map { c ->
                val co = c.asJsonObject()
                CaseFixture(
                    label = co.getValue("label").asJsonString(),
                    matchIndex = (co["matchIndex"] as? Long)?.toInt(),
                    matchingBlocks = co.getValue("matchingBlocks").asJsonArray().map { row -> row.asJsonArray().map { it.asJsonInt() } },
                    ratio = (co["ratio"] as? Number)?.toDouble(),
                    ratioBoarderPenalty = (co["ratioBoarderPenalty"] as? Number)?.toDouble(),
                )
            }
            MatcherFixture(
                sura = obj.getValue("sura").asJsonInt(),
                aya = obj.getValue("aya").asJsonInt(),
                kind = obj.getValue("kind").asJsonString(),
                a = obj.getValue("a").asJsonString(),
                b = obj.getValue("b").asJsonString(),
                cases = cases,
            )
        }
    }
}
