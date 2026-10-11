package com.tarteelwarsh.tilawa.fbank

/**
 * Wrapper Kotlin de knf::OnlineFbank (kaldi-native-fbank, C++, via JNI - voir
 * src/main/cpp/fbank_jni.cpp pour la config exacte, verifiee caractere par
 * caractere contre scripts/compare_fbank_parity.py). Recoit des chunks PCM
 * float32 dans [-1, 1] a 16 kHz (exactement le format produit par
 * core-audio.AudioCapture - pas de conversion d'echelle) via des appels
 * repetes a acceptWaveform(), de taille quelconque : OnlineFbank maintient
 * lui-meme sa position/son historique cumulatif dans le flux (voir
 * core-audio/README.md, section "Contrat gapless").
 *
 * Une instance = une session de reconnaissance. Pas thread-safe (comme
 * l'objet C++ sous-jacent) - a piloter depuis un seul thread/coroutine a la
 * fois, cote appelant (core-pipeline).
 */
class StreamingFbank(config: Config = Config.ZIPFORMER) : AutoCloseable {

    /** Deux frontends Fbank distincts partagent ce moteur (voir fbank_jni.cpp pour le detail des options) : ZIPFORMER (config Zipformer streaming, defaut) et SEGMENTER (reproduit SeamlessM4TFeatureExtractor pour segmenter/final/ - l'appelant doit alors multiplier les echantillons par 2^15 avant acceptWaveform()). */
    enum class Config(val nativeValue: Int) { ZIPFORMER(0), SEGMENTER(1) }

    private var handle: Long = nativeCreate(config.nativeValue)
    private var closed = false

    val featureDim: Int
        get() {
            check(!closed) { "StreamingFbank deja fermee" }
            return nativeFeatureDim(handle)
        }

    val numFramesReady: Int
        get() {
            check(!closed) { "StreamingFbank deja fermee" }
            return nativeNumFramesReady(handle)
        }

    fun acceptWaveform(sampleRate: Float, samples: FloatArray) {
        check(!closed) { "StreamingFbank deja fermee" }
        nativeAcceptWaveform(handle, sampleRate, samples)
    }

    fun isLastFrame(frameIndex: Int): Boolean {
        check(!closed) { "StreamingFbank deja fermee" }
        return nativeIsLastFrame(handle, frameIndex)
    }

    /** Frame Fbank a l'indice absolu donne (0 jusqu'a numFramesReady-1) - FloatArray de taille featureDim. */
    fun getFrame(frameIndex: Int): FloatArray {
        check(!closed) { "StreamingFbank deja fermee" }
        return nativeGetFrame(handle, frameIndex)
    }

    /** Signale la fin du flux (utile en fin de session pour recuperer la toute derniere frame partielle). */
    fun inputFinished() {
        check(!closed) { "StreamingFbank deja fermee" }
        nativeInputFinished(handle)
    }

    /** Purge les n premieres frames (liberation memoire une fois consommees par core-asr) - n'affecte pas la numerotation des frames restantes. */
    fun pop(n: Int) {
        check(!closed) { "StreamingFbank deja fermee" }
        nativePop(handle, n)
    }

    override fun close() {
        if (!closed) {
            nativeDestroy(handle)
            closed = true
        }
    }

    private external fun nativeCreate(config: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeAcceptWaveform(handle: Long, sampleRate: Float, samples: FloatArray)
    private external fun nativeInputFinished(handle: Long)
    private external fun nativeNumFramesReady(handle: Long): Int
    private external fun nativeIsLastFrame(handle: Long, frameIndex: Int): Boolean
    private external fun nativeFeatureDim(handle: Long): Int
    private external fun nativeGetFrame(handle: Long, frameIndex: Int): FloatArray
    private external fun nativePop(handle: Long, n: Int)

    companion object {
        init {
            System.loadLibrary("core_fbank_jni")
        }
    }
}
