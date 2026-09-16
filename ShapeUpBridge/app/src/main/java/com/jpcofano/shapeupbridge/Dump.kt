package com.jpcofano.shapeupbridge

import android.content.Context
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.data.entries.ExerciseSession
import com.samsung.android.sdk.health.data.request.DataTypes
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Estadisticas recalculadas sobre la curva. Se calculan aca, sobre lo que entrego el SDK, y no
 * se toman de los campos meanHeartRate/maxHeartRate de la sesion: el criterio de exito compara
 * la curva contra la verdad de campo, no los resumenes de Samsung contra si mismos.
 */
fun curveStats(session: ExerciseSession): JSONObject {
    val log = session.log
    val stats = JSONObject()
    stats.putRaw("logSize", log?.size)
    if (log == null) {
        stats.put("note", "log nulo: el SDK no entrego lista")
        return stats
    }
    val beats = log.mapNotNull { it.heartRate }
    stats.put("logWithHeartRate", beats.size)
    if (beats.isNotEmpty()) {
        stats.put("meanHeartRate", beats.map { it.toDouble() }.average())
        stats.put("maxHeartRate", beats.max())
        stats.put("minHeartRate", beats.min())
    }
    // Los huecos delatan interpolacion: la verdad de campo espera una pausa real de 17,48 s
    // entre las dos ultimas entradas.
    val times = log.map { it.timestamp.toEpochMilli() }
    if (times.size >= 2) {
        val gaps = times.zipWithNext { a, b -> b - a }
        val maxGap = gaps.max()
        stats.put("maxGapMs", maxGap)
        stats.put("maxGapAtIndex", gaps.indexOf(maxGap))
        stats.put("lastGapMs", gaps.last())
        stats.put("medianGapMs", gaps.sorted()[gaps.size / 2])
    }
    // Los resumenes que trae la propia sesion, para contrastar con lo recalculado.
    val reported = JSONObject()
    reported.putRaw("meanHeartRate", session.meanHeartRate)
    reported.putRaw("maxHeartRate", session.maxHeartRate)
    reported.putRaw("minHeartRate", session.minHeartRate)
    stats.put("reportedBySdk", reported)
    return stats
}

/** La curva con la forma que pide el prompt: [{ "t": epochMs, "hr": bpm }, ...]. */
fun curveArray(session: ExerciseSession): JSONArray {
    val out = JSONArray()
    session.log?.forEach { entry ->
        out.put(
            JSONObject()
                .put("t", entry.timestamp.toEpochMilli())
                .putRaw("hr", entry.heartRate)
        )
    }
    return out
}

/** Fila resumen por sesion para la tabla del reporte. */
fun sessionSummary(point: HealthDataPoint, session: ExerciseSession): JSONObject = JSONObject()
    .putRaw("uid", point.uid)
    .putRaw("appId", point.dataSource?.appId)
    .putRaw("deviceId", point.dataSource?.deviceId)
    .putRaw("startTime", session.startTime)
    .putRaw("endTime", session.endTime)
    .putRaw("exerciseType", session.exerciseType)
    .putRaw("customTitle", session.customTitle)
    .putRaw("duration", session.duration)
    .putRaw("calories", session.calories)
    .putRaw("autoDetected", session.autoDetected)
    .putRaw("logSize", session.log?.size)
    .putRaw("logWithHeartRate", session.log?.count { it.heartRate != null })
    .putRaw("routeSize", session.route?.size)

/**
 * Arma el volcado completo. Devuelve el JSON y, aparte, el peso de la curva sola: el volcado
 * contiene la curva dos veces --cruda dentro de la sesion y en forma {t,hr}-- porque el prompt
 * pide ambas, y para dimensionar M3 hace falta el peso de una sola.
 */
class DumpResult(
    val json: JSONObject,
    val curveBytes: Int,
    val targetFound: Boolean,
)

suspend fun buildDump(
    context: Context,
    reader: BridgeReader,
    permissions: JSONObject,
    runInfo: JSONObject,
): DumpResult {
    val root = JSONObject()
    root.put("generatedAt", instant(Instant.now()))
    root.put("environment", environment(context))
    root.put("run", runInfo)
    root.put("permissions", permissions)
    root.put(
        "ranges",
        JSONObject()
            .put(
                "exercise",
                JSONObject()
                    .put("localFrom", Target.DAY_START.toString())
                    .put("localTo", Target.DAY_END.toString())
            )
            .put(
                "bodyComposition",
                JSONObject()
                    .put("localFrom", Target.BODY_START.toString())
                    .put("localTo", Target.BODY_END.toString())
            )
    )

    val timings = JSONObject()

    var t0 = System.currentTimeMillis()
    val exercisePoints = reader.readExercise()
    timings.put("exerciseReadMs", System.currentTimeMillis() - t0)

    val summaries = JSONArray()
    var target: Pair<HealthDataPoint, ExerciseSession>? = null
    for (point in exercisePoints) {
        for (session in point.sessions()) {
            summaries.put(sessionSummary(point, session))
            if (session.startTime.toEpochMilli() == Target.SESSION_START_EPOCH_MS) {
                target = point to session
            }
        }
    }

    val exercise = JSONObject()
    exercise.put("dataPointCount", exercisePoints.size)
    exercise.put("sessionCount", summaries.length())
    exercise.put("summaries", summaries)
    val rawPoints = JSONArray()
    exercisePoints.forEach { rawPoints.put(it.encodeFull(DataTypes.EXERCISE)) }
    exercise.put("dataPoints", rawPoints)
    root.put("exercise", exercise)

    val targetJson = JSONObject()
    targetJson.put("expectedStartEpochMs", Target.SESSION_START_EPOCH_MS)
    var curveBytes = 0
    if (target == null) {
        targetJson.put("found", false)
    } else {
        val (point, session) = target
        targetJson.put("found", true)
        targetJson.putRaw("uid", point.uid)
        targetJson.putRaw("appId", point.dataSource?.appId)
        targetJson.putRaw("deviceId", point.dataSource?.deviceId)
        targetJson.putRaw("customTitle", session.customTitle)
        targetJson.putRaw("exerciseType", session.exerciseType)
        targetJson.put("stats", curveStats(session))
        val curve = curveArray(session)
        curveBytes = curve.toString().toByteArray(Charsets.UTF_8).size
        targetJson.put("curveBytes", curveBytes)
        targetJson.put("curve", curve)
    }
    root.put("targetSession", targetJson)

    // Sondas por fuente. Solo tienen sentido si la sesion objetivo no trajo curva: si la trajo,
    // releer cuatro veces el dia entero cuesta tiempo y no aporta nada.
    val targetLog = target?.second?.log
    if (targetLog == null || targetLog.size < 10) {
        t0 = System.currentTimeMillis()
        root.put("sourceProbes", reader.probeSources())
        timings.put("sourceProbesMs", System.currentTimeMillis() - t0)
    } else {
        root.put(
            "sourceProbes",
            JSONObject().put("skipped", "la sesion objetivo trajo curva; no hacia falta descartar el filtro de fuente")
        )
    }

    t0 = System.currentTimeMillis()
    val bodyPoints = reader.readBodyComposition()
    timings.put("bodyCompositionReadMs", System.currentTimeMillis() - t0)
    val body = JSONObject()
    body.put("dataPointCount", bodyPoints.size)
    val bodyRaw = JSONArray()
    bodyPoints.forEach { bodyRaw.put(it.encodeFull(DataTypes.BODY_COMPOSITION)) }
    body.put("dataPoints", bodyRaw)
    root.put("bodyComposition", body)

    root.put("timings", timings)
    root.put("errors", reader.errors())

    return DumpResult(root, curveBytes, target != null)
}
