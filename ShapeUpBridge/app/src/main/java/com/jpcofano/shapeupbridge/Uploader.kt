package com.jpcofano.shapeupbridge

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Transporte de PU2: sube cada registro crudo a /ingesta-sdk/{uid}/registros/{docId}.
 * No interpreta nada; los cinco campos del documento estan fijados por las reglas de ShapeUp.
 * Ver docs/prompts/pu2-subida-firestore.md.
 */
class Uploader(
    private val db: FirebaseFirestore,
    private val userUid: String,
) {

    class Result(
        val uploaded: Int,
        val skippedBySize: List<String>,
        val failed: Int,
        val queued: Int,
        val sanitizedIds: List<String>,
        val errorMessages: List<String>,
        val permissionDenied: Boolean,
        val elapsedMs: Long,
    )

    private fun registros() =
        db.collection("ingesta-sdk").document(userUid).collection("registros")

    suspend fun upload(records: List<RawRecord>, versionPuente: String): Result {
        val started = System.currentTimeMillis()
        val skipped = ArrayList<String>()
        val sanitized = ArrayList<String>()

        class Pending(val docId: String, val data: Map<String, Any>, val chars: Int)

        val pending = ArrayList<Pending>()
        for (record in records) {
            val docId = docIdOf(record)
            if (docId != "${record.dataType}_${record.uid}") sanitized.add(docId)
            // El mismo JSONObject que PU1 escribe al archivo, sin indentar.
            val crudo = record.json.toString()
            if (crudo.length > MAX_CRUDO_CHARS) {
                Log.w(TAG, "omitido por tamano: $docId (${crudo.length} caracteres)")
                skipped.add(docId)
                continue
            }
            val data = mapOf(
                "dataType" to record.dataType,
                "uidSamsung" to record.uid,
                "leidoMs" to record.readAtMs,
                "versionPuente" to versionPuente,
                "crudo" to crudo,
            )
            pending.add(Pending(docId, data, crudo.length))
        }

        // Hasta 20 por batch. Ademas se corta antes si el batch se acerca a los 10 MB de un
        // commit: con registros de ~600 KB, 20 juntos lo pasarian.
        val batches = ArrayList<List<Pending>>()
        var current = ArrayList<Pending>()
        var currentChars = 0L
        for (p in pending) {
            if (current.size == MAX_PER_BATCH ||
                (current.isNotEmpty() && currentChars + p.chars > MAX_BATCH_CHARS)
            ) {
                batches.add(current)
                current = ArrayList()
                currentChars = 0
            }
            current.add(p)
            currentChars += p.chars
        }
        if (current.isNotEmpty()) batches.add(current)

        // Se lanzan todos los commits juntos: sin red, Firestore los aplica local y los encola,
        // asi que esperar uno por uno multiplicaria el timeout.
        val tasks: List<Pair<Int, Task<Void>>> = batches.map { batch ->
            val wb = db.batch()
            batch.forEach { wb.set(registros().document(it.docId), it.data) }
            batch.size to wb.commit()
        }

        withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
            for ((_, task) in tasks) runCatching { task.await() }
        }

        var uploaded = 0
        var failed = 0
        var queued = 0
        var denied = false
        val messages = ArrayList<String>()
        for ((size, task) in tasks) {
            when {
                !task.isComplete -> queued += size
                task.isSuccessful -> uploaded += size
                else -> {
                    failed += size
                    val e = task.exception
                    if (e is FirebaseFirestoreException &&
                        e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                    ) denied = true
                    messages.add("${e?.javaClass?.simpleName}: ${e?.message}")
                    Log.e(TAG, "fallo un batch de $size", e)
                }
            }
        }

        return Result(
            uploaded = uploaded,
            skippedBySize = skipped,
            failed = failed,
            queued = queued,
            sanitizedIds = sanitized,
            errorMessages = messages.distinct(),
            permissionDenied = denied,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    /** Una fila de la verificacion de la Parte 4. */
    class Check(val label: String, val expected: String, val actual: String, val ok: Boolean)

    /**
     * Lee de vuelta, del servidor, el documento de la sesion de referencia, parsea su `crudo` y
     * recalcula las cifras con la misma CurveStats que usa PU1. Se fuerza Source.SERVER: leer
     * de la cache local probaria solo que el telefono se acuerda de lo que escribio.
     */
    suspend fun verify(record: RawRecord): List<Check> {
        val snapshot = registros().document(docIdOf(record)).get(Source.SERVER).await()
        val crudo = snapshot.getString("crudo")
            ?: return listOf(Check("documento", "existe con crudo", "no existe", false))

        val sessions = JSONObject(crudo).getJSONObject("fields").optJSONArray("sessions")
            ?: return listOf(Check("sesiones", "presentes", "ausentes", false))
        var log: org.json.JSONArray? = null
        for (i in 0 until sessions.length()) {
            val s = sessions.getJSONObject(i)
            if (s.getJSONObject("startTime").getLong("epochMs") == Target.SESSION_START_EPOCH_MS) {
                log = s.optJSONArray("log")
            }
        }
        if (log == null) return listOf(Check("log", "presente", "ausente", false))

        val times = ArrayList<Long>(log.length())
        val beats = ArrayList<Double?>(log.length())
        for (i in 0 until log.length()) {
            val entry = log.getJSONObject(i)
            times.add(entry.getJSONObject("timestamp").getLong("epochMs"))
            beats.add(if (entry.isNull("heartRate")) null else entry.getDouble("heartRate"))
        }
        val stats = CurveStats.of(times, beats)

        fun int(label: String, expected: Int, actual: Int) =
            Check(label, "$expected", "$actual", expected == actual)

        fun bpm(label: String, expected: Double, actual: Double?, decimals: Int): Check {
            if (actual == null) return Check(label, fmt(expected, decimals), "sin dato", false)
            val shown = round(actual, decimals)
            return Check(label, fmt(expected, decimals), fmt(shown, decimals),
                abs(shown - expected) < 1e-9)
        }

        return listOf(
            int("Entradas en log", 4133, stats.logSize),
            int("Con heartRate", 4112, stats.withHeartRate),
            bpm("Media", 118.213, stats.mean, 3),
            bpm("Maximo", 174.0, stats.max, 0),
            bpm("Minimo", 83.0, stats.min, 0),
        )
    }

    companion object {
        private const val TAG = "ShapeUpBridge"
        const val MAX_CRUDO_CHARS = 1_000_000
        const val MAX_PER_BATCH = 20
        private const val MAX_BATCH_CHARS = 9_000_000L
        const val CONFIRM_TIMEOUT_MS = 15_000L

        /** `{dataType}_{uidSamsung}`; Firestore no acepta `/` en un id. */
        fun docIdOf(record: RawRecord): String =
            "${record.dataType}_${record.uid}".replace('/', '_')

        private fun round(v: Double, decimals: Int): Double {
            var f = 1.0
            repeat(decimals) { f *= 10 }
            return (v * f).roundToLong() / f
        }

        private fun fmt(v: Double, decimals: Int): String = "%.${decimals}f".format(v)
    }
}
