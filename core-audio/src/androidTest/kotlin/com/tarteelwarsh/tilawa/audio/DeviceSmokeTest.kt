package com.tarteelwarsh.tilawa.audio

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Preuve que le pipeline de test instrumente tourne reellement sur un
 * appareil physique (pas d'emulateur - virtualisation materielle non exposee
 * dans cet environnement, et de toute facon ecartee au profit du device
 * physique pour ce projet, voir mobile_app/core-audio/README.md). Rien de
 * fonctionnel n'est encore teste ici - juste que ./gradlew
 * :core-audio:connectedAndroidTest compile, installe et execute
 * effectivement sur le SM-N970F (Android 12, arm64-v8a) avant d'ecrire la
 * moindre ligne de logique de capture audio.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {

    @Test
    fun runsOnRealHardware() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.tarteelwarsh.tilawa.audio.test", context.packageName)

        // Confirme qu'on tourne bien sur du materiel ARM reel, pas un
        // emulateur x86 - le champ le plus direct pour ça est l'ABI
        // rapportee par le systeme.
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull()
        assertTrue("ABI inattendue pour un device ARM reel: $abi", abi == "arm64-v8a" || abi == "armeabi-v7a")

        val micFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        assertTrue("Le device declare ne pas avoir de microphone - inattendu sur ce materiel", micFeature)
    }
}
