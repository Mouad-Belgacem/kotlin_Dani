package com.tarteelwarsh.tilawa.logic.correction

import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonArray
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonInt
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonString
import org.junit.jupiter.api.Test
import kotlin.test.fail

/**
 * Test dore : rejoue des sessions completes (appels successifs a correct()
 * sur le MEME Corrector - stateful, cursor/score/settled/stops evoluent
 * d'un appel a l'autre) et compare a la sortie REELLE de
 * Dani_app/tilawa_correction/Correction.py::Corrector, generee par
 * scripts/generate_corrector_goldens.py (simule le pattern partiel/complet
 * de Dani_app/api/pipeline.py::process_chunk, avec quelques versets
 * volontairement bruites pour exercer les chemins d'erreur).
 */
class CorrectorGoldenTest {

    @Test
    fun `correct() replays sessions identically to the Python reference`() {
        val finder = Finder.fromResources()
        val sessions = loadSessions("/goldens/corrector/corrector_goldens.json")

        val mismatches = mutableListOf<String>()
        for (session in sessions) {
            val corrector = Corrector(finder, cursor = finder.verseToCursor(session.startSura to session.startAya))
            for ((stepIdx, step) in session.steps.withIndex()) {
                val label = "[session ${session.sessionId} step $stepIdx ${step.sura}:${step.aya}/${step.label}]"
                val result = corrector.correct(step.tilawa, wasl = step.wasl)

                if (result.uthmaniText != step.expectedUthmaniText) {
                    mismatches += "$label uthmaniText attendu=${step.expectedUthmaniText} obtenu=${result.uthmaniText}"
                }
                if (result.colors != step.expectedColors) {
                    mismatches += "$label colors attendu=${step.expectedColors} obtenu=${result.colors}"
                }
                if (result.additions != step.expectedAdditions) {
                    mismatches += "$label additions attendu=${step.expectedAdditions} obtenu=${result.additions}"
                }
                if (corrector.cursor != step.cursor) mismatches += "$label cursor attendu=${step.cursor} obtenu=${corrector.cursor}"
                if (corrector.refCursor != step.refCursor) mismatches += "$label refCursor attendu=${step.refCursor} obtenu=${corrector.refCursor}"
                if (corrector.settled != step.settled) mismatches += "$label settled attendu=${step.settled} obtenu=${corrector.settled}"
                if (corrector.stops != step.stops) mismatches += "$label stops attendu=${step.stops} obtenu=${corrector.stops}"
                if (!approxEquals(corrector.score, step.score)) mismatches += "$label score attendu=${step.score} obtenu=${corrector.score}"

                if (mismatches.size >= 20) break
            }
            if (mismatches.size >= 20) break
        }

        if (mismatches.isNotEmpty()) {
            fail("${mismatches.size} divergence(s) (limite a 20 affichees) sur ${sessions.sumOf { it.steps.size }} appels :\n\n" + mismatches.joinToString("\n"))
        }
    }

    private fun approxEquals(x: Double, y: Double, eps: Double = 1e-9) = kotlin.math.abs(x - y) < eps

    private data class Step(
        val sura: Int, val aya: Int, val label: String, val tilawa: String, val wasl: Boolean,
        val expectedUthmaniText: String, val expectedColors: List<Int>, val expectedAdditions: List<Int>,
        val cursor: Int, val refCursor: Int, val score: Double, val settled: Boolean, val stops: Int,
    )

    private data class Session(val sessionId: Int, val startSura: Int, val startAya: Int, val steps: List<Step>)

    private fun loadSessions(resourcePath: String): List<Session> {
        val stream = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
            "Fixture introuvable: $resourcePath - lancer scripts/generate_corrector_goldens.py"
        }
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JsonParser.parse(text).asJsonArray()
        return root.map { entry ->
            val obj = entry.asJsonObject()
            val steps = obj.getValue("steps").asJsonArray().map { s ->
                val so = s.asJsonObject()
                val result = so.getValue("result").asJsonObject()
                val state = so.getValue("state").asJsonObject()
                Step(
                    sura = so.getValue("sura").asJsonInt(),
                    aya = so.getValue("aya").asJsonInt(),
                    label = so.getValue("label").asJsonString(),
                    tilawa = so.getValue("tilawa").asJsonString(),
                    wasl = so.getValue("wasl") as Boolean,
                    expectedUthmaniText = result.getValue("uthmaniText").asJsonString(),
                    expectedColors = result.getValue("colors").asJsonArray().map { it.asJsonInt() },
                    expectedAdditions = result.getValue("additions").asJsonArray().map { it.asJsonInt() },
                    cursor = state.getValue("cursor").asJsonInt(),
                    refCursor = state.getValue("refCursor").asJsonInt(),
                    score = (state.getValue("score") as Number).toDouble(),
                    settled = state.getValue("settled") as Boolean,
                    stops = state.getValue("stops").asJsonInt(),
                )
            }
            Session(
                sessionId = obj.getValue("sessionId").asJsonInt(),
                startSura = obj.getValue("startSura").asJsonInt(),
                startAya = obj.getValue("startAya").asJsonInt(),
                steps = steps,
            )
        }
    }
}
