package com.tarteelwarsh.tilawa.logic.phonetizer

/**
 * Micro-parseur JSON en lecture seule (pas de dependance externe pour ce module
 * pur JVM). Suffisant pour charger phonetizer_rules.json et les fixtures dorees :
 * objets, tableaux, chaines (avec echappements standards + \uXXXX), et entiers.
 *
 * Retourne des Map<String, Any?> / List<Any?> / String / Long / Boolean / null -
 * pas de modele de donnees dedie, le peu d'endroits qui lisent ce resultat font
 * juste un cast cible.
 */
object JsonParser {

    fun parse(text: String): Any? {
        val cursor = Cursor(text)
        cursor.skipWhitespace()
        val value = cursor.parseValue()
        cursor.skipWhitespace()
        return value
    }

    private class Cursor(val text: String) {
        var pos = 0

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        fun expect(literal: String) {
            require(text.regionMatches(pos, literal, 0, literal.length)) {
                "Attendu '$literal' a la position $pos"
            }
            pos += literal.length
        }

        fun parseObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < text.length && text[pos] == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                require(text[pos] == ':') { "':' attendu a la position $pos" }
                pos++
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                when (text[pos]) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; break }
                    else -> error("',' ou '}' attendu a la position $pos")
                }
            }
            return map
        }

        fun parseArray(): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (pos < text.length && text[pos] == ']') { pos++; return list }
            while (true) {
                val value = parseValue()
                list.add(value)
                skipWhitespace()
                when (text[pos]) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; break }
                    else -> error("',' ou ']' attendu a la position $pos")
                }
            }
            return list
        }

        fun parseString(): String {
            require(text[pos] == '"') { "'\"' attendu a la position $pos" }
            pos++
            val sb = StringBuilder()
            while (text[pos] != '"') {
                val c = text[pos]
                if (c == '\\') {
                    pos++
                    when (val esc = text[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append(0x0C.toChar())
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = text.substring(pos + 1, pos + 5)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                        else -> error("Echappement inconnu '\\$esc' a la position $pos")
                    }
                    pos++
                } else {
                    sb.append(c)
                    pos++
                }
            }
            pos++ // closing '"'
            return sb.toString()
        }

        /** Retourne Long pour un entier, Double des qu'un point decimal ou un exposant est present (les fixtures melangent id/track entiers et ratios flottants). */
        fun parseNumber(): Any {
            val start = pos
            if (text[pos] == '-') pos++
            while (pos < text.length && text[pos].isDigit()) pos++
            var isFloat = false
            if (pos < text.length && text[pos] == '.') {
                isFloat = true
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val raw = text.substring(start, pos)
            return if (isFloat) raw.toDouble() else raw.toLong()
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.asJsonObject(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.asJsonArray(): List<Any?> = this as List<Any?>

fun Any?.asJsonString(): String = this as String

fun Any?.asJsonInt(): Int = (this as Long).toInt()

fun Any?.asJsonBoolean(): Boolean = this as Boolean

fun Any?.asJsonDouble(): Double = when (this) {
    is Double -> this
    is Long -> this.toDouble()
    else -> error("valeur JSON non convertible en Double : $this")
}
