package com.tarteelwarsh.tilawa.audio

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Teste AudioCapture sur le vrai microphone du device (aucun equivalent JVM
 * possible - AudioRecord est une API Android reelle). GrantPermissionRule
 * accorde RECORD_AUDIO automatiquement (pas de dialogue UI a gerer dans un
 * test instrumente).
 */
@RunWith(AndroidJUnit4::class)
class AudioCaptureDeviceTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun capturesRealMicAudioAtExpectedFormat(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("RECORD_AUDIO devrait etre accorde par GrantPermissionRule", hasRecordAudioPermission(context))

        val config = AudioConfig(sampleRate = 16000, chunkSamples = 1600) // 100ms/chunk
        val capture = AudioCapture(config)

        // Capture ~1s (10 chunks de 100ms) avec un timeout large - un vrai
        // AudioRecord sur un vrai device, pas de simulation.
        val chunks = withTimeout(10_000) {
            capture.start(context).take(10).toList()
        }
        capture.stop()

        assertEquals(10, chunks.size)
        for (chunk in chunks) {
            assertEquals("chaque chunk doit faire exactement chunkSamples echantillons", config.chunkSamples, chunk.size)
            for (sample in chunk) {
                assertTrue("echantillon hors [-1,1]: $sample", sample in -1f..1f)
            }
        }

        // Pas d'assertion sur le niveau du signal (le silence ambiant est un
        // resultat valide) - juste un log pour inspection manuelle si besoin.
        val rms = kotlin.math.sqrt(chunks.flatMap { it.toList() }.map { it * it }.average())
        android.util.Log.i("AudioCaptureDeviceTest", "RMS mesure sur 1s de capture reelle: $rms")
    }

    @Test
    fun stopReleasesTheAudioRecordCleanly(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val capture = AudioCapture(AudioConfig())

        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch {
            capture.start(context).collect { }
        }
        // Laisse la capture reellement demarrer avant de l'arreter.
        delay(300)
        assertTrue(capture.isCapturing)
        capture.stop()
        withTimeout(5_000) { job.join() }
        assertTrue(!capture.isCapturing)

        // Doit pouvoir redemarrer une nouvelle capture sans exception apres
        // un stop() propre (l'AudioRecord precedent a bien ete libere).
        val restarted = withTimeout(5_000) { capture.start(context).take(1).toList() }
        capture.stop()
        assertEquals(1, restarted.size)
    }

    /**
     * Verifie le contrat "gapless" documente sur AudioCapture (dont depend
     * core-fbank : OnlineFbank.accept_waveform() suppose un flux continu,
     * sans echantillon perdu) - pas suppose, mesure : sur une capture d'une
     * duree cible connue, le nombre total d'echantillons recus doit
     * correspondre au temps ecoule reel, a une tolerance pres. Une perte
     * silencieuse (sous-dimensionnement du buffer interne, consommateur trop
     * lent) ferait chuter ce total en dessous du temps ecoule x sampleRate.
     */
    @Test
    fun deliversSamplesWithoutGapsOverASustainedCapture(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = AudioConfig(sampleRate = 16000, chunkSamples = 1600)
        val capture = AudioCapture(config)

        val targetSamples = 3L * config.sampleRate // ~3s de contenu
        var totalSamples = 0L
        var firstChunkNanos = -1L
        var lastChunkNanos = -1L

        runCatching {
            withTimeout(15_000) {
                capture.start(context).collect { chunk ->
                    val now = System.nanoTime()
                    if (firstChunkNanos == -1L) firstChunkNanos = now
                    lastChunkNanos = now
                    totalSamples += chunk.size
                    // Coupe la collecte une fois la cible atteinte - la
                    // latence d'INIT d'AudioRecord (avant le premier chunk)
                    // n'est volontairement pas mesuree ici, seul l'intervalle
                    // entre premier et dernier chunk recu importe pour
                    // detecter une perte en regime etabli.
                    if (totalSamples >= targetSamples) throw kotlinx.coroutines.CancellationException("cible atteinte")
                }
            }
        }
        capture.stop()

        val elapsedSeconds = (lastChunkNanos - firstChunkNanos) / 1_000_000_000.0
        val expectedSamples = elapsedSeconds * config.sampleRate
        val ratio = totalSamples / expectedSamples

        android.util.Log.i(
            "AudioCaptureDeviceTest",
            "gapless-check: totalSamples=$totalSamples elapsed=${elapsedSeconds}s expected=$expectedSamples ratio=$ratio",
        )
        // Tolerance 10% : le decompte totalSamples inclut le premier ET le
        // dernier chunk, alors que l'intervalle de temps mesure entre leurs
        // arrivees n'en couvre qu'une partie (biais systemique connu, dans
        // le sens "ratio legerement > 1", pas vers une fausse detection de
        // perte) - une vraie perte d'echantillons ferait chuter le ratio
        // bien plus bas que cette marge.
        assertTrue("ratio echantillons recus / attendus suspect (perte possible): $ratio", ratio in 0.90..1.15)
    }
}
