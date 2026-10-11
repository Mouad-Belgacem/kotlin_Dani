package com.tarteelwarsh.tilawa.logic.phonetizer

/**
 * Port fidele du moteur de regles de Dani_app/tilawa_correction/track_phase1.py
 * (fonction ph1T et sa fonction interne repl/update_track). Les tables T1/T2/T3
 * elles-memes ne sont PAS retranscrites a la main ici : elles sont chargees
 * depuis phonetizer_rules.json (serialise mecaniquement par
 * scripts/export_phonetizer_rules.py depuis le Python reel), pour eliminer le
 * risque de coquille de transcription sur ~150 regles.
 */

data class RuleChange(val idx: Int, val occ: Int)

class Rule(val pattern: String, val replacement: String, val changes: List<RuleChange>) {
    val regex: Regex by lazy { Regex(pattern) }

    /** Nombre de groupes capturants DANS ce pattern (hors le wrapper ajoute par CompiledStage) - necessaire pour retrouver le bon numero de groupe une fois toutes les regles combinees en une seule alternance. */
    val ownGroupCount: Int by lazy { regex.toPattern().matcher("").groupCount() }
}

/**
 * Une cascade de regles (T1, T2 ou T3), compilee UNE FOIS en une seule regex
 * combinee "(rule0)|(rule1)|...". Java numerote les groupes capturants en
 * comptant TOUTES les parentheses ouvrantes capturantes de gauche a droite
 * dans le pattern entier - si une regle contient elle-meme des groupes
 * capturants (ex. `(أَنَ|لَّٰكِنَّ)`), ceux-ci decalent la numerotation des
 * regles suivantes. outerGroupIndex[i] est donc precalcule (pas juste i+1)
 * pour retrouver la regle qui a reellement matche, contrepartie de
 * match.lastgroup en Python (qui n'a pas d'equivalent direct en Java 17).
 */
class CompiledStage(val rules: List<Rule>) {
    private val outerGroupIndex: IntArray
    val pattern: java.util.regex.Pattern

    init {
        val indices = IntArray(rules.size)
        var cumulative = 0
        for (i in rules.indices) {
            indices[i] = cumulative + 1
            cumulative += 1 + rules[i].ownGroupCount
        }
        outerGroupIndex = indices
        pattern = java.util.regex.Pattern.compile(rules.joinToString("|") { "(${it.pattern})" })
    }

    /** Index (dans `rules`) de la regle dont le groupe englobant a matche, ou -1. */
    fun ruleIndexOf(matcher: java.util.regex.Matcher): Int {
        for (i in rules.indices) {
            if (matcher.start(outerGroupIndex[i]) != -1) return i
        }
        return -1
    }
}

object PhonetizerRules {
    val T1: CompiledStage by lazy { CompiledStage(loadStage("T1")) }
    val T2: CompiledStage by lazy { CompiledStage(loadStage("T2")) }
    val T3: CompiledStage by lazy { CompiledStage(loadStage("T3")) }

    private fun loadStage(name: String): List<Rule> {
        val resource = requireNotNull(javaClass.getResourceAsStream("/phonetizer_rules.json")) {
            "phonetizer_rules.json introuvable sur le classpath - lancer scripts/export_phonetizer_rules.py"
        }
        val text = resource.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JsonParser.parse(text).asJsonObject()
        return root.getValue(name).asJsonArray().map { entry ->
            val obj = entry.asJsonObject()
            val changes = obj.getValue("changes").asJsonArray().map { pair ->
                val p = pair.asJsonArray()
                RuleChange(p[0].asJsonInt(), p[1].asJsonInt())
            }
            Rule(obj.getValue("pattern").asJsonString(), obj.getValue("replacement").asJsonString(), changes)
        }
    }
}

/** Applique une cascade de regles (un des T1/T2/T3) au texte + track, une fois - miroir d'une iteration de la boucle `for T, rules, pattern in zip(...)` dans ph1T. */
fun applyRuleStage(stage: CompiledStage, text: String, track: List<Int>): Pair<String, List<Int>> {
    val rules = stage.rules

    // Occurrence par defaut = 1, dimensionnee sur TRACK (pas sur le texte) -
    // miroir exact de `occurence = {i: 1 for i in range(len(track))}`. Les
    // deux ne sont PAS garantis de rester a la meme longueur d'une etape a
    // l'autre dans l'original (verifie empiriquement : ex. le verset 2:32 a
    // track_out=196 pour text_out=195 en sortie de T2) - c'est un
    // comportement reel de Dani_app, pas une erreur a corriger ici. Les
    // positions de changement au-dela de track.size sont silencieusement
    // ignorees, comme le ferait le dict Python `occurence.update(...)` dont
    // les cles hors de `range(len(track))` ne sont jamais lues par
    // update_track.
    val occurrence = IntArray(track.size) { 1 }

    // Une seule regex combinee (precompilee dans CompiledStage) - reproduit
    // l'alternance "premiere regle qui matche a cette position gagne",
    // exactement comme le pattern.sub() combine de Python (java.util.regex a
    // la meme semantique d'alternance que re de Python).
    val matcher = stage.pattern.matcher(text)

    val out = StringBuilder()
    var lastEnd = 0
    var searchFrom = 0
    while (searchFrom <= text.length && matcher.find(searchFrom)) {
        val start = matcher.start()
        val end = matcher.end()

        if (end == start) {
            // Match de longueur nulle : avance d'un caractere pour eviter une
            // boucle infinie (comportement de secours, aucune des regles
            // T1/T2/T3 connues ne matche a vide, mais on reste prudent).
            searchFrom = start + 1
            continue
        }

        out.append(text, lastEnd, start)

        val ruleIndex = stage.ruleIndexOf(matcher)
        check(ruleIndex >= 0) { "Aucun groupe de regle n'a matche - incoherent avec un match reussi" }

        val rule = rules[ruleIndex]
        val matchedText = text.substring(start, end)
        val localMatch = rule.regex.matchEntire(matchedText)
            ?: error("La regle individuelle ne matche pas en fullmatch son propre match combine : ${rule.pattern}")

        out.append(expandBackreferences(rule.replacement, localMatch))

        val length = end - start
        for (change in rule.changes) {
            val pos = if (change.idx >= 0) start + change.idx else start + change.idx + length
            if (pos in occurrence.indices) occurrence[pos] = change.occ
        }

        lastEnd = end
        searchFrom = end
    }
    out.append(text, lastEnd, text.length)

    return out.toString() to updateTrack(track, occurrence)
}

private fun updateTrack(track: List<Int>, occurrence: IntArray): List<Int> {
    val newTrack = ArrayList<Int>(track.size)
    for (i in track.indices) {
        val n = occurrence[i]
        if (n > 0) repeat(n) { newTrack.add(track[i]) }
    }
    return newTrack
}

/** Miroir de re.Match.expand() version Python, limite au sous-ensemble utilise par T1/T2/T3 : backreferences \1..\N (multi-chiffres), tout le reste litteral. */
private fun expandBackreferences(replacement: String, match: MatchResult): String {
    val sb = StringBuilder()
    var i = 0
    while (i < replacement.length) {
        val c = replacement[i]
        if (c == '\\' && i + 1 < replacement.length && replacement[i + 1].isDigit()) {
            var j = i + 1
            while (j < replacement.length && replacement[j].isDigit()) j++
            val groupNum = replacement.substring(i + 1, j).toInt()
            sb.append(match.groups[groupNum]?.value ?: "")
            i = j
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}
