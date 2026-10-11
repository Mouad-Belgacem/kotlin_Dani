package com.tarteelwarsh.tilawa.logic.phonetizer

// Port fidele de Dani_app/tilawa_correction/utils.py - ne pas "ameliorer" ou
// simplifier cette logique sans revalider contre le test dore
// (PhonetizerGoldenTest), la fidelite caractere-par-caractere est le but.

val HARAKAT: Set<Char> = setOf('َ', 'ِ', 'ُ')

val HURUF: Set<Char> = setOf(
    'ا', 'ب', 'ت', 'ث', 'ج', 'ح', 'خ', 'د', 'ذ', 'ر', 'ز', 'س', 'ش', 'ص', 'ض',
    'ط', 'ظ', 'ع', 'غ', 'ف', 'ق', 'ك', 'ل', 'م', 'ن', 'ه', 'و', 'ي', 'ء', '۽',
    '$', '@', '*', 'a',
)

val WHITESPACE: Set<Char> = setOf(' ', '\t', '\n', '\r')

/** Position d'un caractere trouve dans le texte, ou (null, null) si absent - miroir du tuple (None, None) de Python. */
data class CharAt(val char: Char?, val index: Int?)

private val NONE = CharAt(null, null)

fun getNextCaracter(index: Int?, text: String): CharAt {
    if (index == null) return NONE
    return if (index + 1 < text.length) CharAt(text[index + 1], index + 1) else NONE
}

fun getNextHarf(index: Int?, text: String): CharAt {
    if (index == null) return NONE
    for (i in (index + 1) until text.length) {
        if (text[i] in HURUF) return CharAt(text[i], i)
    }
    return NONE
}

fun getNextHarfInSameWord(index: Int?, text: String): CharAt {
    if (index == null) return NONE
    for (i in (index + 1) until text.length) {
        if (text[i] in WHITESPACE) break
        if (text[i] in HURUF) return CharAt(text[i], i)
    }
    return NONE
}

fun getPreviousHarfInSameWord(index: Int?, text: String): CharAt {
    if (index == null) return NONE
    for (i in (index - 1) downTo 0) {
        if (text[i] in WHITESPACE) break
        if (text[i] in HURUF) return CharAt(text[i], i)
    }
    return NONE
}

fun getPreviousHaraka(index: Int?, text: String): Char? {
    if (index == null) return null
    if (index - 1 >= 0 && text[index - 1] in HARAKAT) return text[index - 1]
    return null
}

fun isSakin(index: Int?, text: String): Boolean {
    val (nextChar, _) = getNextCaracter(index, text)
    if (index == null) return false
    if (text[index] !in HURUF) return false
    if (nextChar == '$') return true
    // Miroir de : (next_char in huruf or next_char==" ") and next_char not in HARAKAT
    val cond1 = (nextChar != null && nextChar in HURUF) || nextChar == ' '
    val cond2 = nextChar == null || nextChar !in HARAKAT
    if (cond1 && cond2) return true
    return false
}

fun getPreviousHarf(index: Int?, text: String): CharAt {
    if (index == null) return NONE
    for (i in (index - 1) downTo 0) {
        if (text[i] in HURUF) return CharAt(text[i], i)
    }
    return NONE
}

fun safeHarakaAt(idx: Int?, text: String): Char? {
    if (idx == null || idx >= text.length) return null
    return text[idx].takeIf { it in HARAKAT }
}

/**
 * text[i] avec la semantique d'indexation negative de Python (text[-1] = dernier
 * caractere). Deux endroits de bab_alhuruf.py indexent `text[index-1]` sans
 * garde explicite (contrairement aux autres usages de index-1/2/3, qui ont
 * leur propre `if index-N >= 0` ecrit en Python) - remplace ces acces bruts,
 * pour rester fidele si `index==0` s'y presentait jamais (jamais en pratique,
 * la position 0 est toujours '$', mais autant ne pas silencieusement changer
 * le comportement).
 */
fun String.pyAt(i: Int): Char {
    val idx = if (i < 0) length + i else i
    return this[idx]
}
