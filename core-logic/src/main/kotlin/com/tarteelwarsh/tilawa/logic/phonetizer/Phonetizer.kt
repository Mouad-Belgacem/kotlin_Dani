package com.tarteelwarsh.tilawa.logic.phonetizer

// Port fidele de track_phonetize.py::process_phonetic_text et de
// Correction.py::phonetize() (les deux etapes assemblees).

fun processPhoneticText(
    inputText: String,
    track: List<Int>,
    cfg: QiraatConfig = WARSH_TAWASOT_ALBADAL_ICHBAA_ALARID,
): Pair<String, List<Int>> {
    val handlers = buildHandlers(cfg)

    val fragments = StringBuilder()
    var idx = 0
    val length = inputText.length
    val newTrack = ArrayList<Int>()

    while (idx < length) {
        val char = inputText[idx]
        val handler = handlers[char]
        if (handler == null) {
            fragments.append(char)
            newTrack.add(track[idx])
            idx += 1
            continue
        }
        val (res, consumed) = handler(idx, inputText)
        fragments.append(res)
        repeat(res.length) { newTrack.add(track[idx]) }
        idx += consumed
    }

    return fragments.toString() to newTrack
}

data class PhonetizeResult(val phonetic: String, val track: List<Int>)

/** Port fidele de Correction.py::phonetize(). */
fun phonetize(
    aya: String,
    attachLawasiq: Boolean = false,
    cfg: QiraatConfig = WARSH_TAWASOT_ALBADAL_ICHBAA_ALARID,
): PhonetizeResult {
    val wrapped = "$" + aya + "$"
    val phase1 = ph1T(wrapped, attachLawasiq)
    val (final, track) = processPhoneticText(phase1.transformed, phase1.track, cfg)
    return PhonetizeResult(final, track.map { it - 1 })
}
