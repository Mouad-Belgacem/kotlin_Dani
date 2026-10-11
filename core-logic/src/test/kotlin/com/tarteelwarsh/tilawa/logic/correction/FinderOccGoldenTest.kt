package com.tarteelwarsh.tilawa.logic.correction

import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonArray
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonInt
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.fail

/**
 * Niveau intermediaire (voir Finder.kt) : Finder.occAt(c, i) - recherche
 * binaire sur la liste de positions - doit renvoyer EXACTEMENT la meme
 * valeur que la matrice dense self.occ[c, i] du Finder Python original, y
 * compris le cas d'indexation negative numpy occ[c, -1] (utilise par
 * backward_search_c quand First==0). Fixtures generees par
 * scripts/generate_finder_goldens.py, couvrant les frontieres de chaque
 * liste de positions + un echantillon aleatoire large.
 */
class FinderOccGoldenTest {

    @Test
    fun `occAt matches the dense Python occ matrix on every sampled position`() {
        val finder = Finder.fromResources()
        val fixtures = loadFixtures("/goldens/finder/occ_goldens.json")

        val mismatches = mutableListOf<String>()
        for (fixture in fixtures) {
            val actual = finder.occAt(fixture.c, fixture.i)
            if (actual != fixture.occ) {
                mismatches += "c=${fixture.c} i=${fixture.i} attendu=${fixture.occ} obtenu=$actual"
            }
            if (mismatches.size >= 30) break
        }

        if (mismatches.isNotEmpty()) {
            fail("${mismatches.size} divergence(s) (limite a 30 affichees) sur ${fixtures.size} cas :\n\n" + mismatches.joinToString("\n"))
        }
    }

    private data class OccFixture(val c: Int, val i: Int, val occ: Int)

    private fun loadFixtures(resourcePath: String): List<OccFixture> {
        val stream = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
            "Fixture introuvable: $resourcePath - lancer scripts/generate_finder_goldens.py"
        }
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JsonParser.parse(text).asJsonArray()
        return root.map { entry ->
            val obj = entry.asJsonObject()
            OccFixture(obj.getValue("c").asJsonInt(), obj.getValue("i").asJsonInt(), obj.getValue("occ").asJsonInt())
        }
    }
}
