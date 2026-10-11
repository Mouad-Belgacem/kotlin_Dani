package com.tarteelwarsh.tilawa.logic.phonetizer

// Port fidele de Dani_app/tilawa_correction/track_phase1.py::ph1T (sans la
// classe `moshaf`/le choix aleatoire d'aya par defaut - hors-sujet ici, cette
// fonction prend toujours un texte explicite). La ligne Python
// `occurence = {i: 1 for i in range(len(aya))}` juste avant la boucle est
// codee morte (immediatement ecrasee au premier tour de boucle) - omise ici
// volontairement, elle ne fait rien dans l'original non plus.

private val LAWASIQ: Set<Char> = setOf(
    'ُ', '۟', 'ٖ', 'ٛ', 'ْ', 'ٌ', 'ً', 'ّ', 'ٰ', 'َ', 'ٗ', '۬', 'ۨ', '۪', 'ٞ', 'ِ',
)

data class Phase1Result(val transformed: String, val track: List<Int>)

/**
 * `aya` doit commencer et finir par '$' (marqueurs de bordure de fragment) -
 * meme contrat que ph1T, qui leve sinon. Applique T1 puis T2 puis T3 en
 * cascade, chacun via applyRuleStage (RuleEngine.kt) sur les regles chargees
 * de phonetizer_rules.json.
 */
fun ph1T(aya: String, attachLawasiq: Boolean = false): Phase1Result {
    require(aya.isNotEmpty() && aya.first() == '$' && aya.last() == '$') {
        "aya doit commencer et finir par le signe '$'"
    }

    val track = MutableList(aya.length) { it }
    if (attachLawasiq) {
        for (i in aya.indices) {
            if (aya[i] in LAWASIQ) {
                val prevIndex = if (i - 1 >= 0) i - 1 else track.size - 1
                track[i] = track[prevIndex]
            }
        }
    }

    var result = aya
    var currentTrack: List<Int> = track

    for (stage in listOf(PhonetizerRules.T1, PhonetizerRules.T2, PhonetizerRules.T3)) {
        val (newResult, newTrack) = applyRuleStage(stage, result, currentTrack)
        result = newResult
        currentTrack = newTrack
    }

    return Phase1Result(result, currentTrack)
}
