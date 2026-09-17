package com.jpcofano.shapeupbridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.Field
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.data.entries.ExerciseSession
import com.samsung.android.sdk.health.data.device.DeviceGroup
import com.samsung.android.sdk.health.data.helper.SdkVersion
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import com.samsung.android.sdk.health.data.request.ReadDataRequest
import com.samsung.android.sdk.health.data.request.ReadSourceFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/** Parametros de la corrida. Fijos: este PoC verifica una sesion concreta. */
object Target {
    /** Dia de la verdad de campo, en hora local del telefono. */
    val DAY_START: LocalDateTime = LocalDateTime.of(2026, 9, 14, 0, 0, 0)
    val DAY_END: LocalDateTime = LocalDateTime.of(2026, 9, 15, 0, 0, 0)

    /** Sesion "ShapeUp": 2026-09-14T20:34:52.506Z. */
    const val SESSION_START_EPOCH_MS = 1789418092506L

    /** Composicion corporal: 05/09 al 15/09 inclusive. */
    val BODY_START: LocalDateTime = LocalDateTime.of(2026, 9, 5, 0, 0, 0)
    val BODY_END: LocalDateTime = LocalDateTime.of(2026, 9, 16, 0, 0, 0)

    const val SHEALTH_PACKAGE = "com.sec.android.app.shealth"
}

/**
 * Rango de lectura por hora de inicio local. PU1 usa las fechas fijas de la verdad de campo;
 * las corridas de subida (PU3) usan una ventana movil de dias hacia atras.
 */
class ReadRange(
    val exerciseFrom: LocalDateTime,
    val exerciseTo: LocalDateTime,
    val bodyFrom: LocalDateTime,
    val bodyTo: LocalDateTime,
) {
    companion object {
        val PU1 = ReadRange(Target.DAY_START, Target.DAY_END, Target.BODY_START, Target.BODY_END)

        fun lastDays(days: Long, now: LocalDateTime = LocalDateTime.now()): ReadRange {
            val from = now.minusDays(days)
            return ReadRange(from, now, from, now)
        }
    }
}

/**
 * Lee crudo del Samsung Health Data SDK y arma el JSON del volcado. No normaliza, no
 * deduplica, no descarta campos. Ver docs/contexto.md.
 */
class BridgeReader(private val store: HealthDataStore) {

    private val errors = JSONArray()

    fun errors(): JSONArray = errors

    private fun error(where: String, t: Throwable) {
        errors.put(
            JSONObject()
                .put("where", where)
                .put("type", t.javaClass.name)
                .putRaw("message", t.message)
        )
    }

    /**
     * Lee todas las paginas. El SDK devuelve pageToken cuando queda mas data; si no se pagina,
     * un volcado grande se corta en silencio.
     */
    private suspend fun readAllPages(
        label: String,
        newBuilder: () -> ReadDataRequest.DualTimeBuilder<HealthDataPoint>,
    ): List<HealthDataPoint> {
        val out = ArrayList<HealthDataPoint>()
        var token: String? = null
        var pages = 0
        try {
            do {
                val builder = newBuilder()
                if (token != null) builder.setPageToken(token)
                val response = store.readData(builder.build())
                out.addAll(response.dataList)
                token = response.pageToken
                pages++
            } while (token != null && pages < MAX_PAGES)
            if (token != null) {
                error(label, IllegalStateException("se alcanzo el tope de $MAX_PAGES paginas; lectura incompleta"))
            }
        } catch (t: Throwable) {
            error(label, t)
        }
        return out
    }

    private fun exerciseBuilder(
        source: ReadSourceFilter?,
        from: LocalDateTime,
        to: LocalDateTime,
    ): ReadDataRequest.DualTimeBuilder<HealthDataPoint> {
        val builder: ReadDataRequest.DualTimeBuilder<HealthDataPoint> =
            DataTypes.EXERCISE.readDataRequestBuilder
        builder.setLocalTimeFilter(LocalTimeFilter.of(from, to))
        builder.setOrdering(Ordering.ASC)
        if (source != null) builder.setSourceFilter(source)
        return builder
    }

    suspend fun readExercise(range: ReadRange): List<HealthDataPoint> =
        readAllPages("exercise") { exerciseBuilder(null, range.exerciseFrom, range.exerciseTo) }

    suspend fun readBodyComposition(range: ReadRange): List<HealthDataPoint> = readAllPages("bodyComposition") {
        val builder: ReadDataRequest.DualTimeBuilder<HealthDataPoint> =
            DataTypes.BODY_COMPOSITION.readDataRequestBuilder
        builder.setLocalTimeFilter(LocalTimeFilter.of(range.bodyFrom, range.bodyTo))
        builder.setOrdering(Ordering.ASC)
        builder
    }

    /**
     * Relee el mismo dia filtrando por fuente. Sirve para descartar el filtro de fuente como
     * causa de un `log` vacio antes de declarar que no hay datos: lo pide el prompt.
     */
    suspend fun probeSources(range: ReadRange): JSONArray {
        val probes = JSONArray()
        val filters = listOf<Pair<String, ReadSourceFilter>>(
            "watch" to ReadSourceFilter.fromDeviceType(DeviceGroup.WATCH),
            "localDevice" to ReadSourceFilter.fromLocalDevice(),
            "mobile" to ReadSourceFilter.fromDeviceType(DeviceGroup.MOBILE),
            "platform" to ReadSourceFilter.fromPlatform(),
        )
        for ((name, filter) in filters) {
            val points = readAllPages("probe:$name") {
                exerciseBuilder(filter, range.exerciseFrom, range.exerciseTo)
            }
            val sessions = JSONArray()
            for (point in points) {
                for (session in point.sessions()) {
                    sessions.put(
                        JSONObject()
                            .putRaw("uid", point.uid)
                            .putRaw("startTime", session.startTime)
                            .putRaw("customTitle", session.customTitle)
                            .putRaw("exerciseType", session.exerciseType)
                            .putRaw("logSize", session.log?.size)
                            .putRaw("logWithHeartRate", session.log?.count { it.heartRate != null })
                    )
                }
            }
            probes.put(
                JSONObject()
                    .put("filter", name)
                    .put("dataPoints", points.size)
                    .put("sessions", sessions)
            )
        }
        return probes
    }

    companion object {
        private const val MAX_PAGES = 200
    }
}

/** Las sesiones de un punto de ejercicio, o vacio si el campo no vino. */
fun HealthDataPoint.sessions(): List<ExerciseSession> =
    try {
        getValue(DataType.ExerciseType.SESSIONS) ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

/**
 * Vuelca un punto de datos entero. Los campos se recorren con `dataType.allFields`, que es la
 * lista que declara el propio SDK: asi no se omite ninguno por olvido y los nulos se escriben
 * como nulos.
 */
fun HealthDataPoint.encodeFull(dataType: DataType): JSONObject = JSONObject().apply {
    putRaw("uid", uid)
    putRaw("clientDataId", clientDataId)
    putRaw("clientVersion", clientVersion)
    putRaw("appId", dataSource?.appId)
    putRaw("deviceId", dataSource?.deviceId)
    putRaw("startTime", startTime)
    putRaw("endTime", endTime)
    putRaw("updateTime", updateTime)
    putRaw("zoneOffset", zoneOffset)
    putRaw("startLocalDateTime", getStartLocalDateTime())
    putRaw("endLocalDateTime", getEndLocalDateTime())
    val fields = JSONObject()
    for (field in dataType.allFields) {
        @Suppress("UNCHECKED_CAST")
        val typed = field as Field<Any?>
        try {
            fields.putRaw(field.name, getValue(typed))
        } catch (t: Throwable) {
            fields.put(field.name, JSONObject().put("_error", t.javaClass.simpleName))
        }
    }
    put("fields", fields)
}

/** Metadatos del entorno: sin esto el reporte no se puede fechar contra una version. */
fun environment(context: Context): JSONObject {
    val json = JSONObject()
    json.put("sdkVersionName", runCatching { SdkVersion.getVersionName() }.getOrElse { "?" })
    json.put("sdkVersionCode", runCatching { SdkVersion.getVersionCode() }.getOrElse { -1 })
    json.put("aar", "samsung-health-data-api-1.1.0.aar")
    json.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
    json.put("androidRelease", Build.VERSION.RELEASE)
    json.put("androidSdkInt", Build.VERSION.SDK_INT)
    val shealth = JSONObject()
    try {
        val info = context.packageManager.getPackageInfo(Target.SHEALTH_PACKAGE, 0)
        shealth.putRaw("versionName", info.versionName)
        shealth.put("versionCode", info.longVersionCode)
        shealth.put("installed", true)
    } catch (_: PackageManager.NameNotFoundException) {
        shealth.put("installed", false)
    }
    json.put("samsungHealth", shealth)
    return json
}
