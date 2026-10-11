package com.tarteelwarsh.tilawa.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Capture microphone -> chunks PCM float32 dans [-1, 1], a 16 kHz mono - le
 * format attendu par kaldi_native_fbank.OnlineFbank cote core-fbank (verifie
 * contre scripts/compare_fbank_parity.py : scale=1.0, pas de x32768). Aucun
 * equivalent direct dans Dani_app (web : pcm-worklet.js/record.js cote
 * navigateur) - brique specifique au portage Android, testee sur device reel
 * uniquement (voir README - pas d'emulateur dans cet environnement, et
 * de toute facon pas pertinent pour AudioRecord).
 *
 * CONTRAT (dont depend tout ce qui consomme ce flux) : le Flow emis par
 * start() doit etre GAPLESS et STRICTEMENT ORDONNE - chaque chunk suit
 * directement la fin du precedent, sans echantillon perdu ni reordonne.
 * AudioCapture n'expose PAS de position cumulative dans le flux : ce n'est
 * pas necessaire cote core-fbank, puisque kaldi_native_fbank.OnlineFbank
 * (accept_waveform() appelable avec des chunks de taille quelconque)
 * maintient lui-meme sa position/son historique via son etat interne. La
 * taille de chunk ici (300ms par defaut, voir chunkSamples) n'a AUCUN rapport avec la taille de
 * fenetre consommee par le modele Zipformer (chunk_size*2 + PAD_LENGTH = 77
 * frames Fbank = 770ms, avec chevauchement de PAD_LENGTH frames entre deux
 * appels consecutifs - voir scripts/streaming_pipeline_standard.py::
 * SingleStreamBuffer) : ce fenetrage-la vit dans core-asr/core-pipeline, pas
 * ici, a une echelle de temps differente.
 */
data class AudioConfig(
    val sampleRate: Int = 16000,
    // Taille d'un chunk emis, en echantillons - 4800 = 300ms a 16kHz.
    // Relevee de 1600 (100ms, meme granularite que PAD_SAMPLES/
    // MIN_SEGMENT_SAMPLES cote Dani_app) sur decision explicite, pour aller
    // de pair avec EXPERIMENTAL_CTC_SILENCE_THRESHOLD_MS (200ms -> 300ms,
    // core-pipeline/PipelineConfig.kt) pendant l'evaluation du schema
    // "Zipformer seul" - la granularite d'ingestion audio (ici) et le seuil
    // de silence CTC (proxy experimental) sont deux notions distinctes qui
    // n'ont pas besoin d'etre egales, mais l'utilisateur a demande les deux
    // ensemble. S'applique GLOBALEMENT (un seul AudioCapture, partage par
    // les deux modes pipeline) - affecte aussi la granularite du chemin
    // production (PipelineSession) si `USE_EXPERIMENTAL_CTC_ONLY_PIPELINE`
    // est remis a false, pas seulement l'experience.
    val chunkSamples: Int = 4800,
)

class AudioPermissionMissingException :
    IllegalStateException("android.permission.RECORD_AUDIO n'est pas accorde - a demander au niveau UI avant d'appeler AudioCapture.start()")

class AudioRecordInitializationException(message: String) : IllegalStateException(message)

fun hasRecordAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

class AudioCapture(private val config: AudioConfig = AudioConfig()) {

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var capturing = false

    val isCapturing: Boolean get() = capturing

    /**
     * Demarre la capture et retourne un flux de chunks PCM float32 dans
     * [-1, 1]. Un seul capture() actif a la fois - annuler le flux (ou
     * appeler stop()) libere l'AudioRecord.
     */
    fun start(context: Context): Flow<FloatArray> {
        if (!hasRecordAudioPermission(context)) {
            throw AudioPermissionMissingException()
        }
        // Belt and braces: if a previous capture somehow left the flag raised while
        // holding no AudioRecord, recover rather than refuse for the life of the
        // process. A genuine concurrent capture (record still held) is still an error.
        if (capturing && audioRecord == null) capturing = false
        check(!capturing) { "AudioCapture deja demarree - appeler stop() avant de redemarrer" }

        val minBufferBytes = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes == AudioRecord.ERROR || minBufferBytes == AudioRecord.ERROR_BAD_VALUE) {
            throw AudioRecordInitializationException(
                "AudioRecord.getMinBufferSize a echoue (code=$minBufferBytes) pour sampleRate=${config.sampleRate}"
            )
        }
        // Buffer interne AudioRecord dimensionne avec de la marge par
        // rapport a la taille de chunk demandee, pour absorber les
        // irregularites de lecture sans sous-remplissage (underrun).
        val internalBufferBytes = maxOf(minBufferBytes, config.chunkSamples * 2 * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            internalBufferBytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw AudioRecordInitializationException("AudioRecord.state=${record.state} (attendu STATE_INITIALIZED) - microphone indisponible ?")
        }

        audioRecord = record
        capturing = true

        return flow {
            record.startRecording()
            try {
                val shortBuffer = ShortArray(config.chunkSamples)
                while (capturing) {
                    val read = record.read(shortBuffer, 0, config.chunkSamples)
                    if (read > 0) {
                        val floatChunk = FloatArray(read) { i -> shortBuffer[i] / 32768f }
                        emit(floatChunk)
                    } else if (read < 0) {
                        throw AudioRecordInitializationException("AudioRecord.read a renvoye une erreur (code=$read)")
                    }
                }
            } finally {
                // `capturing = false` HERE, not only in stop(). It used to be reset
                // exclusively by an explicit stop(), so every other way this flow can
                // end - the collecting coroutine being CANCELLED, read() returning an
                // error, any exception - released the AudioRecord but left `capturing`
                // true forever. The next start() then hit
                // `check(!capturing)` and threw "AudioCapture deja demarree": the mic
                // never started again, no chunks flowed, no metrics appeared. It also
                // survived closing the app, because swiping from recents usually does
                // not kill the process - the ViewModel and this AudioCapture field
                // live on with the stale flag, so only a real process death cleared
                // it. This is the "works 3-4 times then stops" bug.
                capturing = false
                record.stop()
                record.release()
                if (audioRecord === record) audioRecord = null
            }
        }.flowOn(Dispatchers.IO)
    }

    /** Arrete la capture en cours - le flux retourne par start() se termine (branche `finally`, liberation de l'AudioRecord). */
    fun stop() {
        capturing = false
    }
}
