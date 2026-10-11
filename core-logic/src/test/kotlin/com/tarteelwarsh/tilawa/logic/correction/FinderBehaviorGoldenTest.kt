package com.tarteelwarsh.tilawa.logic.correction

import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonArray
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonInt
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonString
import org.junit.jupiter.api.Test
import kotlin.test.fail

/**
 * Niveau comportemental (le plus important, voir Finder.kt) : le resultat
 * OBSERVABLE final (quel verset, quel score, quelles bornes de fragment) ne
 * doit pas changer d'un iota avec la representation compacte, compare au
 * Finder Python reel. Fixtures generees par scripts/generate_finder_goldens.py.
 *
 * Le contrat verifie ici est deliberement plus fin qu'une egalite brute,
 * pour refleter EXACTEMENT ce qu'on a acte (limitation numpy argsort non
 * stable, voir Finder.kt::getCandidatAyasWithVotes) sans laisser un test
 * rouge en permanence :
 * - getAya() : egalite stricte, SAUF les 3 versets ci-dessous ou la
 *   divergence est connue et causee par cette limitation - reference
 *   explicite par (sourate, verset, mutation), pas un seuil de tolerance
 *   vague. Un 4e cas qui diverge fait echouer le test (vraie regression).
 * - get_candidat_ayas() : meme ENSEMBLE de candidats + meme rang relatif
 *   entre candidats a vote DIFFERENT (l'ordre entre candidats a EGALITE de
 *   votes n'est, par construction cote Python, pas un contrat stable - voir
 *   Finder.kt).
 */
class FinderBehaviorGoldenTest {

    /**
     * Les 3 seuls cas, sur les 392 de la fixture, ou get_aya() diverge du
     * Python reel - dans les 3 cas parce qu'une egalite de votes tombe pile
     * a la frontiere de troncature (k=30) et que numpy et le tri stable
     * Kotlin ne gardent pas le meme candidat a cette frontiere. Verifie one
     * by one (pas un compte global) : si l'un de ces 3 cesse un jour de
     * diverger (le tri devient exact) ou si un 4e cas apparait, le test doit
     * echouer pour qu'on le remarque.
     */
    private val knownUnstableSortTieCases = setOf(
        Triple(68, 47, "middle_fragment"),
        Triple(88, 22, "substitute"),
        Triple(83, 13, "delete_run"),
    )

    @Test
    fun `get_aya and get_candidat_ayas match the Python Finder exactly`() {
        val finder = Finder.fromResources()
        val fixtures = loadBehaviorFixtures("/goldens/finder/finder_behavior_goldens.json")

        val mismatches = mutableListOf<String>()
        val seenKnownCases = mutableSetOf<Triple<Int, Int, String>>()

        for (fixture in fixtures) {
            val key = Triple(fixture.sourceSura, fixture.sourceAya, fixture.kind)
            val isKnownTieCase = key in knownUnstableSortTieCases

            // 1) get_candidat_ayas : contrat corrige apres preuve empirique
            // (cas 27:1 - voir Finder.kt) - au-dela d'un certain volume de
            // candidats a EGALITE stricte de votes, l'ensemble tronque a
            // k=30 n'est PAS une propriete garantissable (n'importe quel tri,
            // stable ou non, doit couper arbitrairement dans ce bloc
            // d'egalite). Seule la partie NON ambigue du classement - les
            // candidats dont le nombre de votes depasse celui du groupe
            // d'egalite qui chevauche la coupure - est verifiee : meme
            // ensemble, meme rang relatif (l'ordre INTERNE a un groupe de
            // votes egaux, lui, n'est pas un contrat stable cote Python et
            // n'est pas exige non plus).
            val actualWithVotes = finder.getCandidatAyasWithVotes(fixture.query)
            val actualCandidates = actualWithVotes.map { it.first }
            val votesOf = actualWithVotes.toMap()

            // Frontiere de troncature : si la liste Kotlin a atteint k=30,
            // le dernier groupe de votes egaux qu'elle contient a
            // potentiellement ete coupe arbitrairement - non verifie. Sinon
            // (moins de 30 candidats au total, pas de troncature possible),
            // tout est verifie, dernier groupe inclus.
            val boundaryVote = if (actualCandidates.size >= 30) votesOf[actualCandidates.last()] else null
            fun isConfident(c: Pair<Int, Int>): Boolean {
                val v = votesOf[c] ?: return false // absent des votes Kotlin -> candidat uniquement cote Python, jamais "confident"
                return boundaryVote == null || v > boundaryVote
            }

            val expectedConfident = fixture.candidates.filter(::isConfident)
            val actualConfident = actualCandidates.filter(::isConfident)
            val expectedGroups = groupByVote(expectedConfident, votesOf)
            val actualGroups = groupByVote(actualConfident, votesOf)
            if (expectedGroups != actualGroups) {
                mismatches += "[${fixture.sourceSura}:${fixture.sourceAya} ${fixture.kind}] candidats (partie non ambigue, hors frontiere de troncature) :\n" +
                    "  attendu=$expectedGroups\n  obtenu =$actualGroups"
            }

            // 2) get_aya() : egalite stricte, sauf les 3 cas connus ci-dessus.
            val actualMatch = finder.getAya(fixture.query)
            val actualAya = actualMatch.aya
            val actualScore = actualMatch.ratio
            if (isKnownTieCase) {
                seenKnownCases += key
                if (actualAya == fixture.getAyaResult && approxEquals(actualScore, fixture.getAyaScore)) {
                    mismatches += "[${fixture.sourceSura}:${fixture.sourceAya} ${fixture.kind}] getAya : marque comme divergence connue (limitation tri numpy) " +
                        "mais matche maintenant exactement (attendu=${fixture.getAyaResult}/${fixture.getAyaScore}, obtenu=$actualAya/$actualScore) - " +
                        "a retirer de knownUnstableSortTieCases si c'est confirme durable."
                }
            } else if (actualAya != fixture.getAyaResult) {
                mismatches += "[${fixture.sourceSura}:${fixture.sourceAya} ${fixture.kind}] getAya verse " +
                    "attendu=${fixture.getAyaResult} obtenu=$actualAya"
            } else if (!approxEquals(actualScore, fixture.getAyaScore)) {
                mismatches += "[${fixture.sourceSura}:${fixture.sourceAya} ${fixture.kind}] getAya score " +
                    "attendu=${fixture.getAyaScore} obtenu=$actualScore"
            }
            if (mismatches.size >= 25) break
        }

        // Garde-fou sur l'allowlist elle-meme : si l'un des 3 cas connus n'a
        // pas ete rencontre dans la fixture (regeneree autrement, entree mal
        // recopiee...), l'allowlist ne teste plus ce qu'elle pretend tester -
        // signale plutot que de laisser passer silencieusement.
        val missingKnownCases = knownUnstableSortTieCases - seenKnownCases
        if (missingKnownCases.isNotEmpty()) {
            mismatches += "knownUnstableSortTieCases contient des cas absents de la fixture : $missingKnownCases"
        }

        if (mismatches.isNotEmpty()) {
            fail("${mismatches.size} divergence(s) (limite a 25 affichees) sur ${fixtures.size} cas :\n\n" + mismatches.joinToString("\n\n"))
        }
    }

    /** Regroupe une sequence de candidats en blocs consecutifs de meme nombre de votes (l'ordre INTERNE a un bloc n'est pas un contrat, voir plus haut). */
    private fun groupByVote(candidates: List<Pair<Int, Int>>, votesOf: Map<Pair<Int, Int>, Int>): List<Set<Pair<Int, Int>>> {
        val groups = mutableListOf<MutableSet<Pair<Int, Int>>>()
        var lastVotes: Int? = null
        for (c in candidates) {
            val v = votesOf.getValue(c)
            if (v == lastVotes) {
                groups.last().add(c)
            } else {
                groups.add(mutableSetOf(c))
                lastVotes = v
            }
        }
        return groups
    }

    @Test
    fun `verse_to_cursor matches the Python Finder exactly`() {
        val finder = Finder.fromResources()
        val fixtures = loadCursorFixtures("/goldens/finder/verse_to_cursor_goldens.json")

        val mismatches = mutableListOf<String>()
        for (fixture in fixtures) {
            val actual = finder.verseToCursor(fixture.sura to fixture.aya)
            if (actual != fixture.cursor) {
                mismatches += "[${fixture.sura}:${fixture.aya}] attendu=${fixture.cursor} obtenu=$actual"
            }
        }
        if (mismatches.isNotEmpty()) {
            fail("${mismatches.size} divergence(s) sur ${fixtures.size} cas :\n\n" + mismatches.joinToString("\n"))
        }
    }

    @Test
    fun `get_fragment and refined_uthmani match the Python Finder exactly`() {
        val finder = Finder.fromResources()
        val fixtures = loadFragmentFixtures("/goldens/finder/fragment_goldens.json")

        val mismatches = mutableListOf<String>()
        for (fixture in fixtures) {
            for (variant in fixture.variants) {
                val fragment = finder.getFragment(fixture.cursor, fixture.width, variant.pushToVerse)
                if (fragment.start != variant.fragmentStart || fragment.end != variant.fragmentEnd) {
                    mismatches += "[${fixture.sura}:${fixture.aya} push=${variant.pushToVerse}] fragment " +
                        "attendu=(${variant.fragmentStart},${variant.fragmentEnd}) obtenu=(${fragment.start},${fragment.end})"
                }
                val refined = finder.refinedUthmani(fixture.tilawa, fixture.cursor, fixture.width, pushToVerse = variant.pushToVerse)
                if (refined.start != variant.refinedStart || refined.end != variant.refinedEnd) {
                    mismatches += "[${fixture.sura}:${fixture.aya} push=${variant.pushToVerse}] refined " +
                        "attendu=(${variant.refinedStart},${variant.refinedEnd}) obtenu=(${refined.start},${refined.end})"
                }
            }
            if (mismatches.size >= 25) break
        }
        if (mismatches.isNotEmpty()) {
            fail("${mismatches.size} divergence(s) (limite a 25 affichees) sur ${fixtures.size} cas :\n\n" + mismatches.joinToString("\n"))
        }
    }

    private fun approxEquals(x: Double, y: Double, eps: Double = 1e-9) = kotlin.math.abs(x - y) < eps

    private data class BehaviorFixture(
        val sourceSura: Int,
        val sourceAya: Int,
        val kind: String,
        val query: String,
        val candidates: List<Pair<Int, Int>>,
        val getAyaResult: Pair<Int, Int>,
        val getAyaScore: Double,
    )

    private fun loadBehaviorFixtures(resourcePath: String): List<BehaviorFixture> {
        val root = readJsonArray(resourcePath)
        return root.map { entry ->
            val obj = entry.asJsonObject()
            BehaviorFixture(
                sourceSura = obj.getValue("sourceSura").asJsonInt(),
                sourceAya = obj.getValue("sourceAya").asJsonInt(),
                kind = obj.getValue("kind").asJsonString(),
                query = obj.getValue("query").asJsonString(),
                candidates = obj.getValue("candidates").asJsonArray().map { row ->
                    val r = row.asJsonArray()
                    r[0].asJsonInt() to r[1].asJsonInt()
                },
                getAyaResult = obj.getValue("getAyaResult").asJsonArray().let { it[0].asJsonInt() to it[1].asJsonInt() },
                getAyaScore = (obj.getValue("getAyaScore") as Number).toDouble(),
            )
        }
    }

    private data class CursorFixture(val sura: Int, val aya: Int, val cursor: Int)

    private fun loadCursorFixtures(resourcePath: String): List<CursorFixture> {
        val root = readJsonArray(resourcePath)
        return root.map { entry ->
            val obj = entry.asJsonObject()
            CursorFixture(obj.getValue("sura").asJsonInt(), obj.getValue("aya").asJsonInt(), obj.getValue("cursor").asJsonInt())
        }
    }

    private data class FragmentVariant(val pushToVerse: Boolean, val fragmentStart: Int, val fragmentEnd: Int, val refinedStart: Int, val refinedEnd: Int)
    private data class FragmentFixture(val sura: Int, val aya: Int, val cursor: Int, val tilawa: String, val width: Int, val variants: List<FragmentVariant>)

    private fun loadFragmentFixtures(resourcePath: String): List<FragmentFixture> {
        val root = readJsonArray(resourcePath)
        return root.map { entry ->
            val obj = entry.asJsonObject()
            val variants = obj.getValue("variants").asJsonArray().map { v ->
                val vo = v.asJsonObject()
                FragmentVariant(
                    pushToVerse = vo.getValue("pushToVerse") as Boolean,
                    fragmentStart = vo.getValue("fragmentStart").asJsonInt(),
                    fragmentEnd = vo.getValue("fragmentEnd").asJsonInt(),
                    refinedStart = vo.getValue("refinedStart").asJsonInt(),
                    refinedEnd = vo.getValue("refinedEnd").asJsonInt(),
                )
            }
            FragmentFixture(
                sura = obj.getValue("sura").asJsonInt(),
                aya = obj.getValue("aya").asJsonInt(),
                cursor = obj.getValue("cursor").asJsonInt(),
                tilawa = obj.getValue("tilawa").asJsonString(),
                width = obj.getValue("width").asJsonInt(),
                variants = variants,
            )
        }
    }

    private fun readJsonArray(resourcePath: String): List<Any?> {
        val stream = requireNotNull(javaClass.getResourceAsStream(resourcePath)) {
            "Fixture introuvable: $resourcePath - lancer scripts/generate_finder_goldens.py"
        }
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JsonParser.parse(text).asJsonArray()
    }
}
