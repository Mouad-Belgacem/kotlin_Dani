package com.tarteelwarsh.tilawa.logic.tokenizer

import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonInt
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject

// Port fidele de scripts/run_aware_tokenizer.py::RunAwareTokenizer.
// encode() = greedy longest-match (jusqu'a maxTokenLen caracteres, repli sur
// unkTokenId si aucune longueur ne matche). decode() = concatenation simple,
// c'est la seule direction reellement utilisee en inference live (decodage
// des ids CTC de core-asr) - encode() est portee pour completude/tests, comme
// dans le script Python d'origine.

class RunAwareTokenizer(
    private val vocab: Map<String, Int>,
    unkToken: String = "<unk>",
    padToken: String = "<pad>",
) {
    private val idToToken: Map<Int, String> = vocab.entries.associate { (k, v) -> v to k }

    val unkTokenId: Int = requireNotNull(vocab[unkToken]) { "'$unkToken' absent du vocab" }
    val padTokenId: Int = requireNotNull(vocab[padToken]) { "'$padToken' absent du vocab" }

    val maxTokenLen: Int = vocab.keys.maxOf { it.length }

    val size: Int get() = vocab.size

    fun encode(text: String): List<Int> {
        val ids = ArrayList<Int>()
        var i = 0
        val n = text.length
        while (i < n) {
            var matched = false
            val maxTry = minOf(maxTokenLen, n - i)
            for (length in maxTry downTo 1) {
                val candidate = text.substring(i, i + length)
                val id = vocab[candidate]
                if (id != null) {
                    ids.add(id)
                    i += length
                    matched = true
                    break
                }
            }
            if (!matched) {
                ids.add(unkTokenId)
                i += 1
            }
        }
        return ids
    }

    /** Concatene les tokens correspondant aux ids, en ignorant les ids inconnus - miroir de decode() (ne filtre pas pad/unk, c'est deja fait en amont par le collapse CTC). */
    fun decode(ids: List<Int>): String = ids.joinToString("") { idToToken[it] ?: "" }

    companion object {
        fun fromResource(resourcePath: String = "/vocab_run_aware.json"): RunAwareTokenizer {
            val stream = requireNotNull(RunAwareTokenizer::class.java.getResourceAsStream(resourcePath)) {
                "$resourcePath introuvable sur le classpath"
            }
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JsonParser.parse(text).asJsonObject()
            val vocab = root.mapValues { (_, v) -> v.asJsonInt() }
            return RunAwareTokenizer(vocab)
        }
    }
}
