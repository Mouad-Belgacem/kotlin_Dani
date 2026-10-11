package com.tarteelwarsh.tilawa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Outil de test UNIQUEMENT (pas dans le mockup, pas un composant produit) :
 * declenche par `adb shell am broadcast`, joue un fichier audio pousse sur
 * l'appareil via le haut-parleur reel - permet un test acoustique de bout
 * en bout (haut-parleur -> micro reel -> pipeline -> affichage) reproductible
 * et chronometrable depuis le shell, sans dependre de la resolution d'un
 * lecteur audio tiers (peu fiable en pratique - AudioLauncherActivity de
 * Chrome n'existe pas comme composant direct, le SoundPicker Samsung ne
 * joue qu'un apercu). Log le debut/la fin avec horodatage pour correler
 * avec les logs de latence de LiveCorrectionViewModel.
 *
 * Bug REEL trouve sur device (pas en relisant le code) : passer un chemin
 * `/sdcard/<fichier>` general (stockage scoped, Android 11+) faisait
 * PLANTER toute l'app - `MediaPlayer.setDataSource()` levait une
 * `FileNotFoundException: EACCES` non rattrapee, propagee jusqu'a
 * `BroadcastReceiver.onReceive()` (pas de try/catch autour). Seul le
 * repertoire externe PROPRE a l'app (`context.getExternalFilesDir(null)`,
 * `/sdcard/Android/data/<package>/files/`) est accessible sans exception
 * scoped-storage particuliere. Fixe des deux cotes : `path` relatif
 * resout desormais contre ce repertoire par defaut (documente dans le
 * message d'erreur si absent), ET tout le chargement/lecture est dans un
 * try/catch - un fichier introuvable/inaccessible ne doit JAMAIS faire
 * planter l'app, meme un outil de test.
 *
 * Deuxieme bug REEL trouve sur device (toujours pas en relisant le code) : la
 * lecture s'arretait net apres ~1s au lieu des ~105s reelles du fichier, sans
 * aucune erreur loguee - `player` etait une simple variable LOCALE de
 * `onReceive()`, seulement retenue en vie par les closures de
 * `setOnCompletionListener`/`setOnErrorListener` ; `onReceive()` retourne
 * immediatement (la lecture est asynchrone), et le `BroadcastReceiver`
 * lui-meme ne vit pas plus longtemps que cet appel - rien n'empechait le GC de
 * collecter l'instance `MediaPlayer` pendant que le native player continuait
 * de jouer en arriere-plan, coupant le son des le premier passage de GC.
 * Fixe en gardant une reference forte dans le companion object (`currentPlayer`),
 * liberee uniquement a la fin/erreur - et en liberant tout lecteur precedent
 * encore actif avant d'en demarrer un nouveau (pas de lecteurs qui
 * s'accumulent si le broadcast est envoye plusieurs fois de suite).
 */
class DebugAudioPlaybackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rawPath = intent.getStringExtra("path") ?: run {
            Log.e(TAG, "extra 'path' manquant")
            return
        }
        // Chemin absolu (commence par "/") utilise tel quel - sinon resolu
        // contre le repertoire externe propre a l'app, le seul garanti
        // accessible sans restriction scoped-storage. `adb push` doit viser
        // CE repertoire (voir app/README.md) - un chemin /sdcard/ general
        // levera une FileNotFoundException EACCES desormais RATTRAPEE
        // (voir plus bas), pas un crash.
        val path = if (rawPath.startsWith("/")) {
            rawPath
        } else {
            File(context.getExternalFilesDir(null), rawPath).absolutePath
        }

        // Un lecteur precedent encore en cours (broadcast renvoye avant la fin
        // du premier) serait sinon abandonne sans etre libere - fuite native.
        currentPlayer?.release()
        currentPlayer = null

        Log.i(TAG, "lecture demarree : $path a ${System.nanoTime()}")
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            player.setDataSource(path)
            player.setOnCompletionListener {
                Log.i(TAG, "lecture terminee a ${System.nanoTime()}")
                it.release()
                if (currentPlayer === it) currentPlayer = null
            }
            player.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "erreur lecture : what=$what extra=$extra")
                mp.release()
                if (currentPlayer === mp) currentPlayer = null
                true
            }
            player.prepare()
            Log.i(TAG, "duree du fichier : ${player.duration}ms")
            player.start()
            // Reference forte retenue jusqu'a la fin/erreur (voir KDoc de
            // classe) - sans ca, le GC peut liberer `player` alors que la
            // lecture native est encore en cours, coupant le son en plein milieu.
            currentPlayer = player
        } catch (e: IOException) {
            Log.e(TAG, "impossible de lire $path - fichier absent/inaccessible (pousser via 'adb push <fichier> ${context.getExternalFilesDir(null)}/<nom>' plutot qu'un /sdcard/ general)", e)
            player.release()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaPlayer dans un etat inattendu pour $path", e)
            player.release()
        }
    }

    private companion object {
        const val TAG = "DebugAudioPlayback"
        var currentPlayer: MediaPlayer? = null
    }
}
