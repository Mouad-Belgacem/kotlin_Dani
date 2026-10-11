package com.tarteelwarsh.tilawa.history

import android.content.Context
import com.tarteelwarsh.tilawa.logic.phonetizer.JsonParser
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonArray
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonInt
import com.tarteelwarsh.tilawa.logic.phonetizer.asJsonObject
import java.io.File

/**
 * Un enregistrement de session terminee - PAS un concept Dani_app (aucune
 * persistance de session n'existe dans Dani_app, web ou API : chaque
 * connexion websocket est ephemere). Ajoute sur demande explicite
 * ("vraie persistance minimale : date, plage recitee, score") plutot que de
 * se contenter de corriger le libelle trompeur du bouton "إنهاء وحفظ الجلسة"
 * (qui ne sauvegardait rien jusqu'ici).
 */
data class SessionRecord(
    val timestampMillis: Long,
    val startSura: Int,
    val startAya: Int,
    val endSura: Int,
    val endAya: Int,
    val correct: Int,
    val duration: Int,
    val error: Int,
    val trust: Double,
    val durationMinutes: Int,
)

/**
 * Stockage JSON plat dans le stockage prive de l'app (`context.filesDir`) -
 * PAS Room/SQLite : a cette echelle (quelques dizaines/centaines
 * d'enregistrements, aucune requete complexe, juste "ajouter" + "lister
 * tout"), une dependance de base de donnees serait hors de proportion.
 * Reutilise le `JsonParser` deja present (core-logic, aucune nouvelle
 * dependance) pour la lecture ; l'ecriture est un petit serialiseur manuel
 * (schema fixe, uniquement des nombres - pas d'echappement de chaine a
 * gerer).
 */
object SessionHistoryStore {
    private const val FILE_NAME = "session_history.json"

    /**
     * Never throws. A corrupt history file must not be able to take the app down:
     * this is read from a coroutine at startup, and an escaping exception there
     * would surface as a crash on every launch - unrecoverable for the user, since
     * the bad file persists. A file we cannot parse is deleted and treated as empty.
     */
    fun load(context: Context): List<SessionRecord> = try {
        loadOrThrow(context)
    } catch (e: Exception) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
        emptyList()
    }

    private fun loadOrThrow(context: Context): List<SessionRecord> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        val text = file.readText(Charsets.UTF_8)
        if (text.isBlank()) return emptyList()
        val array = JsonParser.parse(text).asJsonArray()
        return array.map { entry ->
            val obj = entry.asJsonObject()
            SessionRecord(
                timestampMillis = (obj.getValue("timestampMillis") as Number).toLong(),
                startSura = obj.getValue("startSura").asJsonInt(),
                startAya = obj.getValue("startAya").asJsonInt(),
                endSura = obj.getValue("endSura").asJsonInt(),
                endAya = obj.getValue("endAya").asJsonInt(),
                correct = obj.getValue("correct").asJsonInt(),
                duration = obj.getValue("duration").asJsonInt(),
                error = obj.getValue("error").asJsonInt(),
                trust = (obj.getValue("trust") as Number).toDouble(),
                durationMinutes = obj.getValue("durationMinutes").asJsonInt(),
            )
        }
    }

    /** Ajoute UN enregistrement (relit, ajoute, reecrit - volume attendu trop faible pour justifier un format append-only). */
    fun append(context: Context, record: SessionRecord) {
        val updated = load(context) + record
        val json = buildString {
            append("[")
            updated.forEachIndexed { i, r ->
                if (i > 0) append(",")
                append("{")
                append("\"timestampMillis\":${r.timestampMillis},")
                append("\"startSura\":${r.startSura},")
                append("\"startAya\":${r.startAya},")
                append("\"endSura\":${r.endSura},")
                append("\"endAya\":${r.endAya},")
                append("\"correct\":${r.correct},")
                append("\"duration\":${r.duration},")
                append("\"error\":${r.error},")
                // NaN/Infinity are NOT valid JSON, and Double.toString happily emits
                // them - one such record would make every later load() fail. ratio()
                // can produce NaN (0.0/0.0 when the tilawa is empty), so this is
                // reachable, not theoretical.
                val trust = if (r.trust.isFinite()) r.trust else 0.0
                append("\"trust\":$trust,")
                append("\"durationMinutes\":${r.durationMinutes}")
                append("}")
            }
            append("]")
        }
        File(context.filesDir, FILE_NAME).writeText(json, Charsets.UTF_8)
    }
}
