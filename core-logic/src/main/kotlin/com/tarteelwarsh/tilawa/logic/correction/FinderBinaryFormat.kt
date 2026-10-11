package com.tarteelwarsh.tilawa.logic.correction

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Lecteurs du format binaire compact produit par scripts/build_android_assets.py
// (little-endian, tout en int32). Voir l'en-tete de ce script pour le detail
// des trois layouts (IntArray simple, CSR par caractere de vocab, frontieres
// de verset triees).

private fun littleEndianInts(bytes: ByteArray) =
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()

fun readIntArray(stream: InputStream): IntArray {
    val buf = littleEndianInts(stream.use { it.readBytes() })
    val count = buf.get()
    val out = IntArray(count)
    buf.get(out)
    return out
}

/** CSR (compressed sparse row) : positions[offsets[c] until offsets[c+1]] = positions (triees) du caractere d'indice c dans L. */
class Csr(val offsets: IntArray, val positions: IntArray) {
    fun range(charIndex: Int): IntRange = offsets[charIndex] until offsets[charIndex + 1]
}

fun readCsr(stream: InputStream): Csr {
    val buf = littleEndianInts(stream.use { it.readBytes() })
    val vocabSize = buf.get()
    val offsets = IntArray(vocabSize + 1)
    buf.get(offsets)
    val positions = IntArray(offsets[vocabSize])
    buf.get(positions)
    return Csr(offsets, positions)
}

/** Frontieres de verset triees par `starts` croissant - remplace les tableaux denses index_to_aya / index_to_aya_uthmani (version dense de Python). */
class AyaBoundaries(val starts: IntArray, val ends: IntArray, val suras: IntArray, val ayas: IntArray)

fun readAyaBoundaries(stream: InputStream): AyaBoundaries {
    val buf = littleEndianInts(stream.use { it.readBytes() })
    val count = buf.get()
    val starts = IntArray(count); buf.get(starts)
    val ends = IntArray(count); buf.get(ends)
    val suras = IntArray(count); buf.get(suras)
    val ayas = IntArray(count); buf.get(ayas)
    return AyaBoundaries(starts, ends, suras, ayas)
}
