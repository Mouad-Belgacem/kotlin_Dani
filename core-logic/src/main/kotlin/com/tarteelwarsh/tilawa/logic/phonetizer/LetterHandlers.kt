package com.tarteelwarsh.tilawa.logic.phonetizer

// Port fidele de Dani_app/tilawa_correction/bab_alhuruf.py::build_handlers.
// Chaque handler mirrors sa contrepartie Python le plus litteralement possible
// (memes branches, meme ordre, memes cas limites - y compris deux comparaisons
// mortes dans handle_yah_maddiya qui ne se declenchent jamais en Python non plus,
// signalees en commentaire plutot que "corrigees"). Ne pas simplifier sans
// revalider contre PhonetizerGoldenTest.

data class HandlerResult(val text: String, val consumed: Int)

typealias LetterHandler = (Int, String) -> HandlerResult

private val HURUF_IKHFAA = setOf('ت', 'ث', 'ج', 'د', 'ذ', 'ز', 'س', 'ش', 'ص', 'ض', 'ط', 'ظ', 'ك', 'ف', 'ق')
private val HURUF_IDGHAM_KAMIL = setOf('م', 'ن')
private val HURUF_IDGHAM_NAKIS = setOf('و', 'ي')
private val HURUF_IDGHAM_WITHOUT_GUNNAH = setOf('ل', 'ر')
private val HURUF_QALQALA_NORMAL = setOf('ق', 'ب', 'ج')
private val HURUF_MUTAMATILAN = setOf('ث', 'ح', 'خ', 'ر', 'ز', 'س', 'ش', 'ص', 'ض', 'ظ', 'ع', 'غ', 'ف', 'ك')
private val STANDARD_CARACTERES = setOf('ه', 'ِ', 'َ', '۪', 'ء', 'ُ', '۽') // Python liste aussi "" - une chaine vide ne peut pas etre une cle de dispatch par caractere, omise sans effet
private val TO_REMOVE = setOf('@', '۟', '$', '۬', 'ٰ', ' ', 's', 'a', '£')

private val MAP_POINT = mapOf('۬' to 'َ', '۪' to 'ِ', '۟' to 'ُ')

fun buildHandlers(cfg: QiraatConfig): Map<Char, LetterHandler> {

    val handleSmallWaw: LetterHandler = handler@{ index, text ->
        var res = ""
        val (nxtH, _) = getNextHarf(index, text)
        val (nxtHSameWord, idxNxtHSameWord) = getNextHarfInSameWord(index, text)
        val (prevHSameWord, _) = getPreviousHarfInSameWord(index, text)
        val prevHaraka = getPreviousHaraka(index, text)

        val prevThreeSequences = if (index - 3 >= 0) text.substring(index - 3, index) else null
        val prevTwoSequences = if (index - 2 >= 0) text.substring(index - 2, index) else null

        if (text.pyAt(index - 1) == ' ') {
            // Deux cas distincts en Python, pas une constante : seul un ء trois
            // positions avant donne 2 (ءَولِيَاءُ ۥلَٰءك) ; sinon c'est un madd
            // Badal (فَمَنُ ۥتِيَ).
            return@handler if (text.pyAt(index - 3) == 'ء') {
                HandlerResult("ۥ".repeat(2), 1)
            } else {
                HandlerResult("ۥ".repeat(cfg.maddBadal), 1)
            }
        }
        if (prevThreeSequences == "اِ۟") return@handler HandlerResult("ۥ".repeat(2), 1) // اِ۟وتُمِنَ
        // ا۟وتِيَ : madd Badal, pas 2.
        else if (prevTwoSequences == "ا۟") return@handler HandlerResult("ۥ".repeat(cfg.maddBadal), 1)

        if (prevHaraka != 'ُ') return@handler HandlerResult("", 1)

        if (nxtHSameWord != null) {
            res = when {
                nxtHSameWord == '@' -> "ۥ".repeat(2)
                nxtHSameWord == 'ء' || nxtHSameWord == '۽' -> "ۥ".repeat(cfg.maddMuttasil)
                getNextCaracter(idxNxtHSameWord, text).char == '$' -> "ۥ".repeat(cfg.maddArid)
                isSakin(index + 1, text) -> "ۥ".repeat(cfg.maddLazim)
                prevHSameWord == 'ء' || prevHSameWord == '۽' -> "ۥ".repeat(cfg.maddBadal)
                else -> "ۥ".repeat(2)
            }
        } else {
            res = when {
                nxtH == 'ء' -> "ۥ".repeat(cfg.maddMunfasil)
                isSakin(getNextHarf(index, text).index, text) -> ""
                else -> "ۥ".repeat(2)
            }
        }
        HandlerResult(res, 1)
    }

    val handleWaw: LetterHandler = handler@{ index, text ->
        val prevHaraka = getPreviousHaraka(index, text)
        val (nxtH, idxNxtH) = getNextHarf(index, text)

        // Waw sakin precede de "damma + espace" : madd Badal (ءِنُ وتِيتُم).
        // Premiere branche en Python, avant tout le reste.
        if (isSakin(index, text) && index - 2 >= 0 && text.substring(index - 2, index) == "ُ ") {
            return@handler HandlerResult("ۥ".repeat(cfg.maddBadal), 1)
        }

        if (isSakin(index, text)) {
            if (nxtH == text[index] && isSakin(idxNxtH, text)) {
                return@handler HandlerResult(text[index].toString(), idxNxtH!! - index + 1)
            } else if (nxtH == text[index] && !isSakin(idxNxtH, text)) {
                return@handler HandlerResult(text[index].toString(), 1)
            }
            if (nxtH == 'ء' && prevHaraka == 'َ') {
                return@handler HandlerResult("و".repeat(cfg.maddLeen), 1)
            } else if (index + 2 < text.length && prevHaraka == 'َ') {
                if (text[index + 2] == '$') {
                    return@handler HandlerResult("و".repeat(cfg.maddLeen), 1)
                }
                // Pas de else en Python ici : si text[index+2] != '$', on ne
                // retourne rien dans cette branche et on rejoint le
                // `return "و", 1` final de la fonction.
            } else {
                return@handler HandlerResult("و", 1)
            }
        }
        HandlerResult("و", 1)
    }

    val handleYah: LetterHandler = handler@{ index, text ->
        val prevHaraka = getPreviousHaraka(index, text)
        val (nxtH, _) = getNextHarf(index, text)
        val (previousHarf, idxPreviousHarf) = getPreviousHarf(index, text)
        val harakaOfThePreviousHarf = safeHarakaAt(if (idxPreviousHarf != null) idxPreviousHarf + 1 else null, text)
        val prevThreeSequences = if (index - 3 >= 0) text.substring(index - 3, index) else null

        if (prevThreeSequences == "اُ۪" || prevThreeSequences == "اِ۪" || prevThreeSequences == "اَ۪") {
            return@handler HandlerResult("ے".repeat(2), 1)
        }
        if (index - 1 >= 0 && text[index - 1] == ' ') {
            if (isSakin(index, text)) {
                if (previousHarf == 'ا') {
                    return@handler HandlerResult("ا".repeat(2), 1)
                } else {
                    return@handler when {
                        harakaOfThePreviousHarf == 'َ' && nxtH == 'ت' -> HandlerResult("ا".repeat(2), 1)
                        harakaOfThePreviousHarf == 'ِ' && nxtH == 'ت' -> HandlerResult("ے".repeat(2), 1)
                        harakaOfThePreviousHarf == 'ُ' -> HandlerResult("ۥ".repeat(2), 1)
                        else -> HandlerResult("ي", 1)
                    }
                }
            }
        }

        if (prevHaraka == 'َ' && isSakin(index, text)) {
            if (nxtH == 'ء') {
                return@handler HandlerResult("ي".repeat(cfg.maddLeen), 1)
            } else {
                // is_sakin(index+1), plus "le caractere suivant est $".
                if (isSakin(index + 1, text)) {
                    return@handler HandlerResult("ي".repeat(cfg.maddLeen), 1)
                }
            }
        }

        HandlerResult("ي", 1)
    }

    val handleYahMaddiya: LetterHandler = handler@{ index, text ->
        val (nxtH, idxNxtH) = getNextHarf(index, text)
        val (prevHInSameWord, _) = getPreviousHarfInSameWord(index, text)
        val (nxtHSameWord, idxNxtHSameWord) = getNextHarfInSameWord(index, text)

        if (nxtHSameWord != null) {
            if (nxtHSameWord == '£') {
                return@handler HandlerResult("ے".repeat(6), 2) // ءَلِف لَام مِّي£مَ حَسِبَ
            }
            if (nxtHSameWord == '@') {
                val innerIdx = getNextHarfInSameWord(idxNxtHSameWord, text).index
                return@handler if (isSakin(innerIdx, text)) {
                    HandlerResult("ے".repeat(cfg.maddArid), 2)
                } else {
                    HandlerResult("ے".repeat(2), 2)
                }
            }
            if (nxtHSameWord == '*') {
                return@handler HandlerResult(text[index].toString().repeat(cfg.maddBadal), 2)
            } else if (nxtHSameWord == 'ء') {
                return@handler HandlerResult("ے".repeat(cfg.maddMuttasil), 1)
            } else if (isSakin(idxNxtHSameWord, text)) {
                return@handler if (getNextCaracter(idxNxtHSameWord, text).char == '$') {
                    HandlerResult(text[index].toString().repeat(cfg.maddArid), 1)
                } else {
                    HandlerResult("ے".repeat(cfg.maddLazim), 1)
                }
                // CORRECTION d'un contresens du portage precedent : il traitait
                // `x in ['ء', "۽"]` comme une comparaison a une liste, donc
                // toujours fausse, et omettait les deux branches. En Python `in`
                // sur une liste est un test d'APPARTENANCE, parfaitement valide
                // pour un caractere. La seconde (madd Badal) est bien atteinte -
                // son absence rendait `ءِ` + madd en 2 au lieu de madd_badal.
            } else if (nxtHSameWord == 'ء' || nxtHSameWord == '۽') {
                // 'ء' seul est deja capte plus haut ; en pratique ne reste que '۽'.
                return@handler HandlerResult("ے".repeat(cfg.maddMuttasil), 1)
            } else if (prevHInSameWord == 'ء' || prevHInSameWord == '۽') {
                return@handler HandlerResult("ے".repeat(cfg.maddBadal), 1) // Madd Badal
            } else {
                return@handler HandlerResult("ے".repeat(2), 1)
            }
        } else {
            return@handler when {
                nxtH == 'ء' -> HandlerResult("ے".repeat(cfg.maddMunfasil), 1)
                prevHInSameWord == 'ء' -> HandlerResult("ے".repeat(cfg.maddBadal), 1)
                isSakin(idxNxtH, text) -> if (nxtH == 'و') HandlerResult("ے".repeat(2), 3) else HandlerResult("", 1)
                else -> HandlerResult("ے".repeat(2), 1)
            }
        }
    }

    val handleAnd: LetterHandler = { _, _ -> HandlerResult("ا".repeat(cfg.maddBadal), 2) }

    val handlePlus: LetterHandler = { _, _ -> HandlerResult("ے".repeat(6), 2) }

    val handleChapeau: LetterHandler = { index, text ->
        val nxtCaracter = getNextCaracter(index, text).char
        if (nxtCaracter == 'ا' || nxtCaracter == 'ۥ' || nxtCaracter == 'ے') {
            HandlerResult(nxtCaracter.toString().repeat(cfg.maddBadal), 2)
        } else {
            HandlerResult("", 1)
        }
    }

    val handleAlif: LetterHandler = handler@{ index, text ->
        val prevHaraka = getPreviousHaraka(index, text)
        val (nxtH, idxNxtH) = getNextHarf(index, text)
        val (prevHarf, _) = getPreviousHarfInSameWord(index, text)
        val nxtCaracter = getNextCaracter(index, text).char
        val (nextHSameWord, idxNextHSameWord) = getNextHarfInSameWord(index, text)

        if (prevHarf == 'a') {
            return@handler HandlerResult("ا".repeat(cfg.maddLazim), 1)
        }

        if (prevHarf == 'ف' || prevHarf == 'و' || prevHarf == 'ك') {
            if (isSakin(idxNextHSameWord, text)) {
                if (index + 2 < text.length && text[index + 2] == '$') {
                    return@handler HandlerResult("ا".repeat(cfg.maddArid), 1) // من وال$
                } else if (nextHSameWord != 'ف') {
                    return@handler HandlerResult("", 1) // والحمد
                }
                // next_h_same_word == 'ف' : pas de `else` en Python, on retombe
                // dans la suite - كَاف ه۪ا ي۪ا عَين صَاد.
            } else {
                // فَلَ / كَلَ : l'alif tombe au lieu d'etre allongee.
                // text[index+1:index+3] en Python ne leve jamais : une tranche
                // qui depasse rend simplement une chaine plus courte, donc
                // differente de "لَ". regionMatches a la meme semantique ici.
                if ((prevHarf == 'ف' || prevHarf == 'ك') && text.regionMatches(index + 1, "لَ", 0, 2)) {
                    return@handler HandlerResult("", 1)
                }
                // sinon on retombe dans la suite (map_point etc.) comme si ce
                // premier bloc n'existait pas.
            }
        }

        handleAlifTail(index, text, prevHaraka, nxtH, idxNxtH, prevHarf, nxtCaracter, nextHSameWord, idxNextHSameWord, cfg)
    }

    val handleNoon: LetterHandler = { index, text ->
        val (nxtH, _) = getNextHarf(index, text)
        val (nxtHSameWord, _) = getNextHarfInSameWord(index, text)

        if (!isSakin(index, text)) {
            HandlerResult("ن", 1)
        } else if (nxtHSameWord != null) {
            when {
                nxtHSameWord == 'ن' -> HandlerResult("ن".repeat(4), 2)
                nxtHSameWord in HURUF_IKHFAA -> HandlerResult("ںں", 1)
                nxtHSameWord in HURUF_IDGHAM_NAKIS -> HandlerResult("ن", 1)
                nxtHSameWord == 'ب' -> HandlerResult("۾۾", 1)
                else -> HandlerResult("ن", 1)
            }
        } else {
            when {
                nxtH != null && nxtH in HURUF_IKHFAA -> HandlerResult("ںں", 1)
                nxtH != null && nxtH in HURUF_IDGHAM_KAMIL -> HandlerResult(nxtH.toString().repeat(4), 4)
                nxtH != null && nxtH in HURUF_IDGHAM_NAKIS -> HandlerResult(nxtH.toString().repeat(3), 3)
                nxtH != null && nxtH in HURUF_IDGHAM_WITHOUT_GUNNAH -> HandlerResult("", 1)
                nxtH == 'ب' -> HandlerResult("۾۾", 2)
                else -> HandlerResult("ن", 1)
            }
        }
    }

    val handleMim: LetterHandler = { index, text ->
        val (nextH, _) = getNextHarf(index, text)
        val (nextHSameWord, _) = getNextHarfInSameWord(index, text)

        if (!isSakin(index, text)) {
            HandlerResult("م", 1)
        } else if (nextHSameWord != null) {
            when (nextHSameWord) {
                'م' -> HandlerResult("م".repeat(4), 2)
                'ب' -> HandlerResult("۾۾", 1)
                else -> HandlerResult("م", 1)
            }
        } else {
            when (nextH) {
                'ب' -> HandlerResult("۾۾", 1)
                'م' -> HandlerResult("م".repeat(4), 4)
                else -> HandlerResult("م", 1)
            }
        }
    }

    val handleQalqalaNormal: LetterHandler = { index, text ->
        val (nextH, idxNextH) = getNextHarf(index, text)
        if (isSakin(index, text)) {
            if (nextH == text[index] && isSakin(idxNextH, text)) {
                HandlerResult(text[index].toString(), idxNextH!! - index + 1)
            } else if (nextH == text[index] && !isSakin(idxNextH, text)) {
                HandlerResult(text[index].toString(), 1)
            } else {
                HandlerResult(text[index].toString() + "چ", 1)
            }
        } else {
            HandlerResult(text[index].toString(), 1)
        }
    }

    val handleDal: LetterHandler = { index, text ->
        val (nextH, idxNextH) = getNextHarf(index, text)
        if (isSakin(index, text) && (nextH == 'ت' || nextH == 'ظ' || nextH == 'ض')) {
            HandlerResult("", 1)
        } else if (isSakin(index, text)) {
            if (nextH == text[index] && isSakin(idxNextH, text)) {
                HandlerResult(text[index].toString(), idxNextH!! - index + 1)
            } else if (nextH == text[index] && !isSakin(idxNextH, text)) {
                HandlerResult(text[index].toString(), 1)
            } else {
                HandlerResult("د" + "چ", 1)
            }
        } else {
            HandlerResult("د", 1)
        }
    }

    val handleTha: LetterHandler = { index, text ->
        val (nextH, idxNextH) = getNextHarf(index, text)
        if (isSakin(index, text) && nextH == 'ت') {
            HandlerResult("ڟ", 1)
        } else if (isSakin(index, text)) {
            if (nextH == text[index] && isSakin(idxNextH, text)) {
                HandlerResult(text[index].toString(), idxNextH!! - index + 1)
            } else if (nextH == text[index] && !isSakin(idxNextH, text)) {
                HandlerResult(text[index].toString(), 1)
            } else {
                HandlerResult("ط" + "چ", 1)
            }
        } else {
            HandlerResult("ط", 1)
        }
    }

    val handleTa: LetterHandler = { index, text ->
        val (nextH, idxNextH) = getNextHarf(index, text)
        if (isSakin(index, text)) {
            if (nextH == 'ط' || nextH == 'د' || nextH == 'ظ') {
                HandlerResult("", 1)
            } else if (nextH == text[index] && isSakin(idxNextH, text)) {
                HandlerResult(text[index].toString(), idxNextH!! - index + 1)
            } else {
                HandlerResult(text[index].toString(), 1)
            }
        } else {
            HandlerResult("ت", 1)
        }
    }

    val handleDhal: LetterHandler = { index, text ->
        val (nextH, idxNextH) = getNextHarf(index, text)
        if (isSakin(index, text)) {
            if (nextH == 'ظ') {
                HandlerResult("", 1)
            } else if (nextH == text[index] && isSakin(idxNextH, text)) {
                HandlerResult(text[index].toString(), idxNextH!! - index + 1)
            } else {
                HandlerResult(text[index].toString(), 1)
            }
        } else {
            HandlerResult(text[index].toString(), 1)
        }
    }

    val handleLam: LetterHandler = { index, text ->
        val (nextH, _) = getNextHarf(index, text)
        val (nextHSameWord, _) = getNextHarfInSameWord(index, text)

        if (isSakin(index, text)) {
            if (nextHSameWord != null) {
                HandlerResult("ل", 1)
            } else if (nextH == 'ل' || nextH == 'ر') {
                HandlerResult("", 2)
            } else {
                HandlerResult("ل", 1)
            }
        } else {
            HandlerResult("ل", 1)
        }
    }

    val handleMutamatilan: LetterHandler = { index, text ->
        val (nextHarf, idxNextHarf) = getNextHarf(index, text)
        if (isSakin(index, text)) {
            if (nextHarf == text[index] && isSakin(idxNextHarf, text)) {
                HandlerResult(text[index].toString(), idxNextHarf!! - index + 1)
            } else {
                HandlerResult(text[index].toString(), 1)
            }
        } else {
            HandlerResult(text[index].toString(), 1)
        }
    }

    val handleStandardChar: LetterHandler = { index, text -> HandlerResult(text[index].toString(), 1) }

    val handleRemove: LetterHandler = { _, _ -> HandlerResult("", 1) }

    val handlers = LinkedHashMap<Char, LetterHandler>()
    handlers['و'] = handleWaw
    handlers['ۥ'] = handleSmallWaw
    handlers['ي'] = handleYah
    handlers['ا'] = handleAlif
    handlers['ٛ'] = handleChapeau
    handlers['ے'] = handleYahMaddiya
    handlers['ن'] = handleNoon
    handlers['م'] = handleMim
    handlers['د'] = handleDal
    handlers['ط'] = handleTha
    handlers['ت'] = handleTa
    handlers['ذ'] = handleDhal
    handlers['ل'] = handleLam
    handlers['&'] = handleAnd
    handlers['+'] = handlePlus

    for (c in STANDARD_CARACTERES) handlers[c] = handleStandardChar
    for (c in TO_REMOVE) handlers[c] = handleRemove
    for (c in HURUF_QALQALA_NORMAL) handlers[c] = handleQalqalaNormal
    for (c in HURUF_MUTAMATILAN) handlers[c] = handleMutamatilan

    return handlers
}

/** Suite de handle_alif au-dela du premier bloc `prev_harf in ['ف','و','ك']` - extrait en fonction separee pour eviter de dupliquer ~50 lignes deux fois dans la lambda (le code Python, lui, ne duplique rien car son fallthrough est implicite ; ici la duplication de point d'entree est rendue explicite par les deux appels ci-dessus). */
private fun handleAlifTail(
    index: Int,
    text: String,
    prevHaraka: Char?,
    nxtH: Char?,
    idxNxtH: Int?,
    prevHarf: Char?,
    nxtCaracter: Char?,
    nextHSameWord: Char?,
    idxNextHSameWord: Int?,
    cfg: QiraatConfig,
): HandlerResult {
    // Deviation deliberee : le Python indexe text[index+2]/text[index+1] sans
    // garde de longueur ici (IndexError si depasse). Les gardes de longueur
    // ci-dessous n'ont jamais ete observees se declencher sur le corpus doré
    // (les 6330 versets passent a l'identique) - gardees par robustesse
    // plutot que par fidelite au crash, puisque cette fonction ne tourne que
    // sur le texte de reference du Coran, jamais sur une transcription ASR.
    if (prevHaraka != 'َ' && prevHaraka != null) {
        return HandlerResult("", 1)
    }

    if (text.pyAt(index - 1) == '$') {
        return when {
            index + 2 < text.length && text[index + 2] in MAP_POINT -> HandlerResult("ء" + MAP_POINT.getValue(text[index + 2]), 3)
            index + 1 < text.length && (text[index + 1] == 'َ' || text[index + 1] == 'ُ' || text[index + 1] == 'ِ') ->
                HandlerResult("ء" + text[index + 1], 2)
            index + 1 < text.length && text[index + 1] in MAP_POINT -> HandlerResult("ء" + MAP_POINT.getValue(text[index + 1]), 2)
            index + 1 < text.length && text[index + 1] == 'ٰ' -> {
                if (isSakin(idxNextHSameWord, text)) {
                    HandlerResult("ءَ" + "ا".repeat(cfg.maddLazim), 2)
                } else {
                    HandlerResult("ءَ" + "ا".repeat(cfg.maddBadal), 2)
                }
            }
            else -> HandlerResult(text.substring(index, idxNextHSameWord!!), idxNextHSameWord - index)
        }
    }

    if (nextHSameWord != null) {
        return when {
            nextHSameWord == '$' -> HandlerResult("ا".repeat(2), 1)
            nextHSameWord == '@' -> HandlerResult("ا".repeat(2), 2)
            nextHSameWord == 'ء' || nextHSameWord == '۽' -> HandlerResult("ا".repeat(cfg.maddMuttasil), 1)
            isSakin(idxNextHSameWord, text) -> when {
                index + 1 < text.length && text[index + 1] == 'ٰ' -> HandlerResult("ا".repeat(cfg.maddLazim), 2)
                index + 1 < text.length && text[index + 1] == '۟' -> HandlerResult("", 2)
                getNextCaracter(idxNextHSameWord, text).char == '$' -> HandlerResult(text[index].toString().repeat(cfg.maddArid), 1)
                else -> HandlerResult("ا".repeat(cfg.maddLazim), 1)
            }
            prevHarf == 'ء' || prevHarf == '۽' -> HandlerResult("ا".repeat(cfg.maddBadal), 1)
            index + 1 < text.length && text[index + 1] == 'ٰ' -> HandlerResult("ا".repeat(cfg.maddBadal), 2)
            else -> HandlerResult("ا".repeat(2), 1)
        }
    } else {
        return when {
            isSakin(idxNxtH, text) -> HandlerResult("", 1)
            nxtH == 'ء' || nxtH == '۽' -> HandlerResult("ا".repeat(cfg.maddMunfasil), 1)
            nxtCaracter == 'ٰ' -> HandlerResult("ا".repeat(cfg.maddBadal), 2)
            prevHarf == 'ء' || prevHarf == '۽' -> HandlerResult("ا".repeat(cfg.maddBadal), 1)
            else -> HandlerResult("ا".repeat(2), 1)
        }
    }
}
