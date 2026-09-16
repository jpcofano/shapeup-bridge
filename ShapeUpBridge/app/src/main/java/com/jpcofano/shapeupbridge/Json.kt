package com.jpcofano.shapeupbridge

import com.samsung.android.sdk.health.data.data.entries.ExerciseLocation
import com.samsung.android.sdk.health.data.data.entries.ExerciseLog
import com.samsung.android.sdk.health.data.data.entries.ExerciseSession
import com.samsung.android.sdk.health.data.data.entries.SwimmingLog
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Serializacion cruda. El puente no normaliza: un campo nulo se escribe como null, un cero se
 * escribe como cero. Cual campo viene nulo es lo que distingue una fuente de la otra, asi que
 * ninguna clave se omite. Ver docs/fuentes-y-reglas.md.
 */

/** Pone el valor sin perder el null: JSONObject.put(k, null) borraria la clave. */
fun JSONObject.putRaw(name: String, value: Any?): JSONObject = put(name, encode(value))

fun encode(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is Instant -> instant(value)
    is Duration -> JSONObject()
        .put("ms", value.toMillis())
        .put("iso", value.toString())
    is LocalDateTime -> value.toString()
    is ZoneOffset -> value.id
    is Enum<*> -> value.name
    is ExerciseSession -> encodeSession(value)
    is ExerciseLog -> encodeLog(value)
    is ExerciseLocation -> encodeLocation(value)
    is SwimmingLog -> encodeSwimmingLog(value)
    is List<*> -> JSONArray().also { arr -> value.forEach { arr.put(encode(it)) } }
    is Float -> if (value.isFinite()) value else value.toString()
    is Double -> if (value.isFinite()) value else value.toString()
    is Int, is Long, is Boolean, is String -> value
    else -> value.toString()
}

fun instant(t: Instant): JSONObject = JSONObject()
    .put("epochMs", t.toEpochMilli())
    .put("iso", t.toString())

/**
 * Todos los getters publicos de ExerciseSession, en el orden del .aar. Se enumeran a mano
 * porque son la carga util del PoC y se quiere que falte uno en tiempo de compilacion, no
 * en silencio.
 */
fun encodeSession(s: ExerciseSession): JSONObject = JSONObject().apply {
    putRaw("startTime", s.startTime)
    putRaw("endTime", s.endTime)
    putRaw("duration", s.duration)
    putRaw("exerciseType", s.exerciseType)
    putRaw("customTitle", s.customTitle)
    putRaw("calories", s.calories)
    putRaw("distance", s.distance)
    putRaw("altitudeGain", s.altitudeGain)
    putRaw("altitudeLoss", s.altitudeLoss)
    putRaw("count", s.count)
    putRaw("countType", s.countType)
    putRaw("maxSpeed", s.maxSpeed)
    putRaw("meanSpeed", s.meanSpeed)
    putRaw("maxCalorieBurnRate", s.maxCalorieBurnRate)
    putRaw("meanCalorieBurnRate", s.meanCalorieBurnRate)
    putRaw("maxCadence", s.maxCadence)
    putRaw("meanCadence", s.meanCadence)
    putRaw("maxHeartRate", s.maxHeartRate)
    putRaw("meanHeartRate", s.meanHeartRate)
    putRaw("minHeartRate", s.minHeartRate)
    putRaw("maxAltitude", s.maxAltitude)
    putRaw("minAltitude", s.minAltitude)
    putRaw("inclineDistance", s.inclineDistance)
    putRaw("declineDistance", s.declineDistance)
    putRaw("maxPower", s.maxPower)
    putRaw("meanPower", s.meanPower)
    putRaw("maxRpm", s.maxRpm)
    putRaw("meanRpm", s.meanRpm)
    putRaw("comment", s.comment)
    putRaw("vo2Max", s.vo2Max)
    putRaw("autoDetected", s.autoDetected)
    putRaw("swimmingLog", s.swimmingLog)
    // log y route se vuelcan como conteo + lista completa. Si vienen nulos se escribe null,
    // no una lista vacia: el prompt pide distinguir "no hay lista" de "lista de cero".
    putRaw("logSize", s.log?.size)
    putRaw("logWithHeartRate", s.log?.count { it.heartRate != null })
    putRaw("routeSize", s.route?.size)
    putRaw("log", s.log)
    putRaw("route", s.route)
}

fun encodeLog(l: ExerciseLog): JSONObject = JSONObject().apply {
    putRaw("timestamp", l.timestamp)
    putRaw("heartRate", l.heartRate)
    putRaw("cadence", l.cadence)
    putRaw("count", l.count)
    putRaw("power", l.power)
    putRaw("speed", l.speed)
}

fun encodeLocation(p: ExerciseLocation): JSONObject = JSONObject().apply {
    putRaw("timestamp", p.timestamp)
    putRaw("latitude", p.latitude)
    putRaw("longitude", p.longitude)
    putRaw("altitude", p.altitude)
    putRaw("accuracy", p.accuracy)
}

fun encodeSwimmingLog(s: SwimmingLog): JSONObject = JSONObject().apply {
    putRaw("poolLength", s.poolLength)
    putRaw("poolLengthUnit", s.poolLengthUnit)
    putRaw("totalDistance", s.totalDistance)
    putRaw("totalDuration", s.totalDuration)
    putRaw("swimmingIntervals", s.swimmingIntervals?.map { it.toString() })
}
