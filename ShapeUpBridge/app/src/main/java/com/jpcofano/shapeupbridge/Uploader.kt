package com.jpcofano.shapeupbridge

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Transporte: sube cada registro crudo a /ingesta-sdk/{uid}/registros/{docId}. No interpreta
 * nada. Un registro de mas de [partThresholdBytes] se parte en documentos `__p{i}` cuya
 * concatenacion reconstruye el `crudo` exacto. Ver docs/prompts/pu2-subida-firestore.md y
 * docs/prompts/pu3-segundo-plano.md.
 */
class Uploader(
    private val db: FirebaseFirestore,
    private val userUid: String,
    private val hashes: UploadHashes,
) {

    class Result(
        val read: Int,
        val uploaded: Int,
        val unchanged: Int,
        val queued: Int,
        /** Registros de mas de [MAX_PARTS] partes: se omiten y cuentan tambien como error. */
        val skippedTooManyParts: List<String>,
        /** Registros cuyo batch fallo. */
        val failed: Int,
        /** Registros partidos: docId base -> cantidad de partes. */
        val split: Map<String, Int>,
        val sanitizedIds: List<String>,
        val errorMessages: List<String>,
        val permissionDenied: Boolean,
        val networkFailure: Boolean,
        val elapsedMs: Long,
    )

    private fun registros() =
        db.collection("ingesta-sdk").document(userUid).collection("registros")

    private sealed class Op {
        abstract val docId: String

        class Put(override val docId: String, val data: Map<String, Any>, val bytes: Int) : Op()
        class Delete(override val docId: String) : Op()
    }

    private class Plan(val base: String, val hash: String, val parts: Int, val ops: List<Op>) {
        val batches = HashSet<Int>()
    }

    suspend fun upload(records: List<RawRecord>, versionPuente: String): Result {
        val started = System.currentTimeMillis()
        val tooManyParts = ArrayList<String>()
        val sanitized = ArrayList<String>()
        val split = LinkedHashMap<String, Int>()
        var unchanged = 0

        val plans = ArrayList<Plan>()
        for (record in records) {
            val base = docIdOf(record)
            if (base != "${record.dataType}_${record.uid}") sanitized.add(base)
            // El mismo JSONObject que PU1 escribe al archivo, sin indentar.
            val crudo = record.json.toString()
            val hash = sha256(crudo)
            val previous = hashes.get(base)
            if (previous?.hash == hash) {
                unchanged++
                continue
            }
            val common = mapOf(
                "dataType" to record.dataType,
                "uidSamsung" to record.uid,
                "leidoMs" to record.readAtMs,
                "versionPuente" to versionPuente,
            )
            val ops = ArrayList<Op>()
            val previousParts = previous?.parts ?: 0
            val bytes = utf8Length(crudo)
            val parts: Int
            if (bytes <= partThresholdBytes) {
                parts = 0
                ops.add(Op.Put(base, common + ("crudo" to crudo), bytes))
                // Si antes se subio partido, las partes viejas no pueden convivir con el entero.
                for (i in 1..previousParts) ops.add(Op.Delete(partId(base, i)))
            } else {
                val chunks = splitUtf8(crudo, partThresholdBytes)
                if (chunks.size > MAX_PARTS) {
                    Log.w(TAG, "omitido: $base necesita ${chunks.size} partes (${bytes} bytes)")
                    tooManyParts.add(base)
                    continue
                }
                parts = chunks.size
                split[base] = parts
                chunks.forEachIndexed { index, chunk ->
                    val i = index + 1
                    ops.add(
                        Op.Put(
                            partId(base, i),
                            common + mapOf("crudo" to chunk, "parte" to i, "totalPartes" to parts),
                            utf8Length(chunk),
                        )
                    )
                    // El entero va en el mismo batch que la primera parte.
                    if (i == 1) ops.add(Op.Delete(base))
                }
                for (i in parts + 1..previousParts) ops.add(Op.Delete(partId(base, i)))
            }
            plans.add(Plan(base, hash, parts, ops))
        }

        // Hasta 20 documentos escritos por batch, y se corta antes de los ~9 MB. Un registro
        // partido puede quedar repartido en varios batches.
        val batchOps = ArrayList<MutableList<Op>>()
        var current = ArrayList<Op>()
        var puts = 0
        var bytesInBatch = 0L
        for (plan in plans) {
            for (op in plan.ops) {
                if (op is Op.Put && current.isNotEmpty() &&
                    (puts == MAX_PER_BATCH || bytesInBatch + op.bytes > MAX_BATCH_BYTES)
                ) {
                    batchOps.add(current)
                    current = ArrayList()
                    puts = 0
                    bytesInBatch = 0
                }
                current.add(op)
                if (op is Op.Put) {
                    puts++
                    bytesInBatch += op.bytes
                }
                plan.batches.add(batchOps.size)
            }
        }
        if (current.isNotEmpty()) batchOps.add(current)

        // Se lanzan todos los commits juntos: sin red, Firestore los aplica local y los encola.
        val tasks: List<Task<Void>> = batchOps.map { ops ->
            val wb = db.batch()
            for (op in ops) {
                val ref = registros().document(op.docId)
                when (op) {
                    is Op.Put -> wb.set(ref, op.data)
                    is Op.Delete -> wb.delete(ref)
                }
            }
            wb.commit()
        }

        // Se espera mientras haya progreso: se abandona recien cuando un commit no confirma en
        // 15 s, que es el caso "sin red". Una subida grande con red no queda marcada en cola.
        for (task in tasks) {
            val done = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { runCatching { task.await() } }
            if (done == null) break
        }

        var denied = false
        var network = false
        val messages = ArrayList<String>()
        for (task in tasks) {
            if (task.isComplete && !task.isSuccessful) {
                val e = task.exception
                if (e is FirebaseFirestoreException) {
                    when (e.code) {
                        FirebaseFirestoreException.Code.PERMISSION_DENIED -> denied = true
                        FirebaseFirestoreException.Code.UNAVAILABLE,
                        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> network = true
                        else -> Unit
                    }
                }
                messages.add("${e?.javaClass?.simpleName}: ${e?.message}")
                Log.e(TAG, "fallo un batch", e)
            }
        }

        var uploaded = 0
        var queued = 0
        var failed = 0
        for (plan in plans) {
            val own = plan.batches.map { tasks[it] }
            when {
                own.any { it.isComplete && !it.isSuccessful } -> failed++
                own.all { it.isComplete } -> {
                    uploaded++
                    hashes.put(plan.base, UploadHashes.Entry(plan.hash, plan.parts))
                }
                else -> {
                    // Escrito en la cola local de Firestore, que sobrevive reinicios.
                    queued++
                    hashes.put(plan.base, UploadHashes.Entry(plan.hash, plan.parts))
                }
            }
        }
        if (queued > 0) network = true
        hashes.save()

        tooManyParts.forEach {
            messages.add(0, "omitido: $it supera $MAX_PARTS partes")
        }

        return Result(
            read = records.size,
            uploaded = uploaded,
            unchanged = unchanged,
            queued = queued,
            skippedTooManyParts = tooManyParts,
            failed = failed,
            split = split,
            sanitizedIds = sanitized,
            errorMessages = messages.distinct(),
            permissionDenied = denied,
            networkFailure = network,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    /** Una fila de la verificacion contra la sesion de referencia. */
    class Check(val label: String, val expected: String, val actual: String, val ok: Boolean)

    class Verification(val docIds: List<String>, val checks: List<Check>)

    /**
     * Lee de vuelta, del servidor, el documento de la sesion de referencia --entero o todas sus
     * partes--, reconstruye el `crudo` y recalcula las cifras con la misma CurveStats de PU1.
     * Se fuerza Source.SERVER: leer de la cache solo probaria que el telefono se acuerda.
     */
    suspend fun verify(record: RawRecord): Verification {
        val base = docIdOf(record)
        val parts = hashes.get(base)?.parts ?: 0
        val ids = ArrayList<String>()
        val crudo = StringBuilder()
        if (parts == 0) {
            ids.add(base)
            val snap = registros().document(base).get(Source.SERVER).await()
            val text = snap.getString("crudo")
                ?: return Verification(ids, listOf(Check("documento", "existe con crudo", "no existe", false)))
            crudo.append(text)
        } else {
            var total = parts
            var i = 1
            while (i <= total) {
                val id = partId(base, i)
                ids.add(id)
                val snap = registros().document(id).get(Source.SERVER).await()
                val text = snap.getString("crudo")
                    ?: return Verification(ids, listOf(Check("parte $i", "existe", "no existe", false)))
                val parte = snap.getLong("parte")?.toInt()
                val totalPartes = snap.getLong("totalPartes")?.toInt()
                if (parte != i || totalPartes == null) {
                    return Verification(
                        ids,
                        listOf(Check("parte $i", "parte=$i con totalPartes", "parte=$parte totalPartes=$totalPartes", false)),
                    )
                }
                total = totalPartes
                crudo.append(text)
                i++
            }
        }

        val sessions = JSONObject(crudo.toString()).getJSONObject("fields").optJSONArray("sessions")
            ?: return Verification(ids, listOf(Check("sesiones", "presentes", "ausentes", false)))
        var log: JSONArray? = null
        for (i in 0 until sessions.length()) {
            val s = sessions.getJSONObject(i)
            if (s.getJSONObject("startTime").getLong("epochMs") == Target.SESSION_START_EPOCH_MS) {
                log = s.optJSONArray("log")
            }
        }
        if (log == null) return Verification(ids, listOf(Check("log", "presente", "ausente", false)))

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

        return Verification(
            ids,
            listOf(
                Check("Crudo reconstruido igual al leido", "identico",
                    if (crudo.toString() == record.json.toString()) "identico" else "distinto",
                    crudo.toString() == record.json.toString()),
                int("Entradas en log", 4133, stats.logSize),
                int("Con heartRate", 4112, stats.withHeartRate),
                bpm("Media", 118.213, stats.mean, 3),
                bpm("Maximo", 174.0, stats.max, 0),
                bpm("Minimo", 83.0, stats.min, 0),
            ),
        )
    }

    companion object {
        private const val TAG = "ShapeUpBridge"

        /**
         * Solo para la prueba forzada de PU3: baja el umbral a 200.000 bytes para que la sesion
         * de referencia (~591 KB) se parta en 3. En uso normal va en false.
         */
        const val DEBUG_FORCE_SMALL_PARTS = false

        val partThresholdBytes: Int = if (DEBUG_FORCE_SMALL_PARTS) 200_000 else 900_000
        const val MAX_PARTS = 50
        const val MAX_PER_BATCH = 20
        private const val MAX_BATCH_BYTES = 9_000_000L
        const val CONFIRM_TIMEOUT_MS = 15_000L

        /** `{dataType}_{uidSamsung}`; Firestore no acepta `/` en un id. */
        fun docIdOf(record: RawRecord): String =
            "${record.dataType}_${record.uid}".replace('/', '_')

        fun partId(base: String, i: Int): String = "${base}__p$i"

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private fun utf8Bytes(codePoint: Int): Int = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }

        fun utf8Length(text: String): Int {
            var total = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                total += utf8Bytes(cp)
                i += Character.charCount(cp)
            }
            return total
        }

        /**
         * Corta en trozos de hasta [maxBytes] bytes UTF-8 sin partir un caracter: el corte cae
         * siempre entre puntos de codigo, y un par sustituto se mueve entero.
         */
        fun splitUtf8(text: String, maxBytes: Int): List<String> {
            val out = ArrayList<String>()
            var start = 0
            var bytes = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val b = utf8Bytes(cp)
                if (bytes + b > maxBytes && i > start) {
                    out.add(text.substring(start, i))
                    start = i
                    bytes = 0
                }
                bytes += b
                i += Character.charCount(cp)
            }
            if (start < text.length || out.isEmpty()) out.add(text.substring(start))
            return out
        }

        private fun round(v: Double, decimals: Int): Double {
            var f = 1.0
            repeat(decimals) { f *= 10 }
            return (v * f).roundToLong() / f
        }

        private fun fmt(v: Double, decimals: Int): String = "%.${decimals}f".format(v)
    }
}

/**
 * Hash SHA-256 del `crudo` de cada docId subido, y en cuantas partes se subio. Es
 * deduplicacion de transporte: si se pierde, se reenvia todo, que es idempotente.
 */
class UploadHashes(private val file: File) {

    class Entry(val hash: String, val parts: Int)

    private val entries = HashMap<String, Entry>()

    init {
        try {
            if (file.exists()) {
                val json = JSONObject(file.readText())
                for (key in json.keys()) {
                    val e = json.getJSONObject(key)
                    entries[key] = Entry(e.getString("h"), e.optInt("p", 0))
                }
            }
        } catch (t: Throwable) {
            Log.w("ShapeUpBridge", "hashes ilegibles; se reenvia todo", t)
            entries.clear()
        }
    }

    fun get(docId: String): Entry? = entries[docId]

    fun put(docId: String, entry: Entry) {
        entries[docId] = entry
    }

    val isEmpty: Boolean get() = entries.isEmpty()

    fun save() {
        val json = JSONObject()
        for ((k, v) in entries) json.put(k, JSONObject().put("h", v.hash).put("p", v.parts))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        fun open(filesDir: File) = UploadHashes(File(filesDir, "hashes-subida.json"))
    }
}
