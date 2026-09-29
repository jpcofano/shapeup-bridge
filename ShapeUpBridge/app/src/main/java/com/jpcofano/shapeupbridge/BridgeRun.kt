package com.jpcofano.shapeupbridge

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

enum class Origin(val value: String) {
    MANUAL("manual"),
    BACKGROUND("segundo-plano"),
    // P90: la corrida que dispara un push de ShapeUp.
    REQUESTED("pedido"),
}

/** Lo que queda de una corrida: es el documento estado/puente y una fila del historial local. */
class RunSummary(
    val endedMs: Long,
    val version: String,
    val origin: String,
    val read: Int,
    val uploaded: Int,
    val unchanged: Int,
    val skipped: Int,
    val errors: Int,
    val durationMs: Long,
    val message: String?,
    // Solo locales: no van a Firestore.
    val windowDays: Int,
    val queued: Int,
    val note: String?,
    // P96: el push que genero esta corrida (solo en las "pedido"). Local, no va a Firestore.
    val push: JSONObject? = null,
) {
    /** Exactamente los campos que aceptan las reglas de estado/puente. */
    fun toEstado(): Map<String, Any> {
        val map = linkedMapOf<String, Any>(
            "ultimaCorridaMs" to endedMs,
            "versionPuente" to version,
            "origen" to origin,
            "leidos" to read,
            "subidos" to uploaded,
            "sinCambios" to unchanged,
            "omitidos" to skipped,
            "errores" to errors,
            "duracionMs" to durationMs,
        )
        if (errors > 0 && message != null) map["mensaje"] = message
        return map
    }

    fun toJson(): JSONObject = JSONObject()
        .put("endedMs", endedMs).put("version", version).put("origin", origin)
        .put("read", read).put("uploaded", uploaded).put("unchanged", unchanged)
        .put("skipped", skipped).put("errors", errors).put("durationMs", durationMs)
        .putRaw("message", message).put("windowDays", windowDays).put("queued", queued)
        .putRaw("note", note)
        .also { if (push != null) it.put("push", push) }

    companion object {
        fun fromJson(j: JSONObject) = RunSummary(
            endedMs = j.getLong("endedMs"),
            version = j.optString("version"),
            origin = j.optString("origin"),
            read = j.optInt("read"),
            uploaded = j.optInt("uploaded"),
            unchanged = j.optInt("unchanged"),
            skipped = j.optInt("skipped"),
            errors = j.optInt("errors"),
            durationMs = j.optLong("durationMs"),
            message = if (j.isNull("message")) null else j.optString("message"),
            windowDays = j.optInt("windowDays"),
            queued = j.optInt("queued"),
            note = if (j.isNull("note")) null else j.optString("note"),
            push = j.optJSONObject("push"),
        )
    }
}

/** Las ultimas 20 corridas, en filesDir, la mas nueva primero. */
object RunHistory {
    private const val MAX = 20
    private fun file(context: Context) = File(context.filesDir, "corridas.json")

    @Synchronized
    fun all(context: Context): List<RunSummary> = try {
        val f = file(context)
        if (!f.exists()) emptyList() else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { RunSummary.fromJson(arr.getJSONObject(it)) }
        }
    } catch (t: Throwable) {
        Log.w("ShapeUpBridge", "historial ilegible", t)
        emptyList()
    }

    @Synchronized
    fun add(context: Context, summary: RunSummary) {
        val list = (listOf(summary) + all(context)).take(MAX)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        file(context).writeText(arr.toString())
    }
}

class RunOutcome(
    val summary: RunSummary,
    val dump: DumpResult?,
    val upload: Uploader.Result?,
    val uploader: Uploader?,
    val file: File?,
    val failure: Throwable?,
    val readerErrors: JSONArray,
    val networkFailure: Boolean,
)

/**
 * La corrida de lectura y subida. La usan el boton "Leer y subir" y el worker; solo cambian
 * el origen y si se escribe el archivo de volcado de PU1.
 */
object BridgeRun {
    private const val TAG = "ShapeUpBridge"
    const val WINDOW_DAYS = 14
    const val FIRST_WINDOW_DAYS = 90
    private const val PREFS = "bridge"
    private const val KEY_FIRST_DONE = "ventanaInicialHecha"

    // Una corrida por vez en el proceso. Un push y la periodica pueden arrancar juntas al volver
    // la red, y las dos guardan el mismo archivo de hashes: la segunda espera y encuentra todo
    // sin cambios.
    private val running = Mutex()

    val PERMISSIONS = setOf(
        Permission.of(DataTypes.EXERCISE, AccessType.READ),
        Permission.of(DataTypes.HEART_RATE, AccessType.READ),
        Permission.of(DataTypes.BODY_COMPOSITION, AccessType.READ),
    )

    fun windowDays(context: Context): Int =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FIRST_DONE, false))
            WINDOW_DAYS else FIRST_WINDOW_DAYS

    fun writeDumpFile(context: Context, json: JSONObject): File {
        val dir = File(context.filesDir, "salidas").apply { mkdirs() }
        val file = File(dir, "volcado-m1.json")
        file.writeText(json.toString(2))
        return file
    }

    suspend fun execute(
        context: Context,
        origin: Origin,
        userUid: String,
        log: (String) -> Unit = {},
    ): RunOutcome = running.withLock { executeLocked(context, origin, userUid, log) }

    /**
     * P96: el push que genero esta corrida "pedido": el ultimo registrado, solo si llego despues
     * de la corrida "pedido" anterior. Si no (el test de KEEP, que pide sin push), queda null en
     * vez de atribuirle un push viejo.
     */
    private fun pushOfThisRun(context: Context): JSONObject? {
        val push = PushLog.last(context) ?: return null
        val previous = RunHistory.all(context).firstOrNull { it.origin == Origin.REQUESTED.value }
        if (previous != null && push.receivedMs <= previous.endedMs) return null
        return push.toJson()
    }

    private suspend fun executeLocked(
        context: Context,
        origin: Origin,
        userUid: String,
        log: (String) -> Unit,
    ): RunOutcome {
        val app = context.applicationContext
        // Un token puede cambiar sin que llegue onNewToken: se revisa en cada corrida.
        DeviceToken.sync(app, userUid)?.let { log("token: $it") }
        val started = System.currentTimeMillis()
        val days = windowDays(app)
        val errors = ArrayList<String>()
        var readerErrors = JSONArray()
        var dump: DumpResult? = null
        var result: Uploader.Result? = null
        var uploader: Uploader? = null
        var file: File? = null
        var failure: Throwable? = null
        var readerErrorCount = 0

        try {
            val store = HealthDataService.getStore(app)
            val granted = store.getGrantedPermissions(PERMISSIONS)
            val missing = PERMISSIONS - granted
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "faltan permisos de Samsung Health: " + missing.joinToString { it.dataType.name }
                )
            }
            val permissionsJson = JSONObject()
                .put("granted", JSONArray(granted.map { it.dataType.name }.sorted()))
            val runInfo = JSONObject()
                .put("origin", origin.value)
                .put("windowDays", days)
                .put("timeZone", ZoneId.systemDefault().id)
                .put("startedAt", instant(Instant.ofEpochMilli(started)))

            log("leyendo los ultimos $days dias (${origin.value}) ...")
            val reader = BridgeReader(store)
            val range = ReadRange.lastDays(days.toLong())
            dump = withContext(Dispatchers.Default) {
                buildDump(app, reader, permissionsJson, runInfo, range, probeIfTargetMissing = false)
            }
            readerErrors = reader.errors()
            readerErrorCount = readerErrors.length()
            for (i in 0 until readerErrors.length()) {
                val e = readerErrors.getJSONObject(i)
                errors.add("${e.optString("where")}: ${e.optString("type")}: ${e.optString("message")}")
            }

            if (origin == Origin.MANUAL) {
                dump.json.getJSONObject("timings").put("totalMs", System.currentTimeMillis() - started)
                file = withContext(Dispatchers.IO) { writeDumpFile(app, dump.json) }
            }

            log("subiendo ${dump.records.size} registros ...")
            val hashes = withContext(Dispatchers.IO) { UploadHashes.open(app.filesDir) }
            uploader = Uploader(FirebaseFirestore.getInstance(), userUid, hashes)
            result = withContext(Dispatchers.Default) {
                uploader.upload(dump.records, BuildConfig.VERSION_NAME)
            }
            errors.addAll(result.errorMessages)
            if (result.permissionDenied) {
                errors.add(0, "PERMISSION_DENIED al escribir registros: probablemente falta desplegar la regla")
            }
        } catch (t: Throwable) {
            failure = t
            Log.e(TAG, "fallo la corrida ${origin.value}", t)
            errors.add(0, "${t.javaClass.simpleName}: ${t.message}")
        }

        val errorCount = when {
            failure != null -> 1
            else -> readerErrorCount + (result?.failed ?: 0) + (result?.skippedTooManyParts?.size ?: 0)
        }
        val ended = System.currentTimeMillis()
        var summary = RunSummary(
            endedMs = ended,
            version = BuildConfig.VERSION_NAME,
            origin = origin.value,
            read = dump?.records?.size ?: 0,
            // Lo encolado por Firestore ya esta escrito localmente y se entrega solo.
            uploaded = (result?.uploaded ?: 0) + (result?.queued ?: 0),
            unchanged = result?.unchanged ?: 0,
            skipped = result?.skippedTooManyParts?.size ?: 0,
            errors = errorCount,
            durationMs = ended - started,
            message = errors.firstOrNull()?.take(300),
            windowDays = days,
            queued = result?.queued ?: 0,
            note = null,
            push = if (origin == Origin.REQUESTED) pushOfThisRun(app) else null,
        )

        // Estado de la corrida en Firestore. Si falla, queda anotado en el historial local.
        val estadoError: String? = try {
            val task = FirebaseFirestore.getInstance()
                .collection("ingesta-sdk").document(userUid)
                .collection("estado").document("puente")
                .set(summary.toEstado())
            val confirmed = withTimeoutOrNull(Uploader.CONFIRM_TIMEOUT_MS) { task.await(); true }
            if (confirmed == null) "estado/puente en cola (sin confirmar)" else null
        } catch (t: Throwable) {
            Log.e(TAG, "no se pudo escribir estado/puente", t)
            "estado/puente: ${t.javaClass.simpleName}: ${t.message}"
        }
        if (estadoError != null) {
            summary = RunSummary(
                summary.endedMs, summary.version, summary.origin, summary.read, summary.uploaded,
                summary.unchanged, summary.skipped, summary.errors, summary.durationMs,
                summary.message, summary.windowDays, summary.queued, estadoError.take(300),
                summary.push,
            )
        }
        withContext(Dispatchers.IO) { RunHistory.add(app, summary) }

        // La ventana larga se consume recien cuando una corrida leyo y subio sin errores.
        if (failure == null && errorCount == 0 && (result?.queued ?: 0) == 0) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_FIRST_DONE, true).apply()
        }

        return RunOutcome(
            summary = summary,
            dump = dump,
            upload = result,
            uploader = uploader,
            file = file,
            failure = failure,
            readerErrors = readerErrors,
            networkFailure = result?.networkFailure == true,
        )
    }
}
