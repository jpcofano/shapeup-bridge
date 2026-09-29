package com.jpcofano.shapeupbridge

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * P90: ShapeUp pide una corrida con un push de datos, silencioso. No abre la app ni muestra
 * notificacion; solo encola el worker de siempre como corrida "pedido".
 */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.i(TAG, "token FCM nuevo")
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        scope.launch { DeviceToken.sync(applicationContext, uid, token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val receivedMs = System.currentTimeMillis()
        Log.i(TAG, "push recibido: prioridad=${message.priority} original=${message.originalPriority} enviadoMs=${message.sentTime} datos=${message.data}")

        // P96: se espera el encolado. FCM suelta su wakelock cuando este metodo vuelve; si el job
        // todavia no esta en JobScheduler, la CPU se duerme y el pedido espera al proximo
        // despertar ajeno (28/09: 72 s). Este metodo corre en un hilo de fondo de FCM, no en el
        // principal, asi que bloquear un momento es valido.
        val started = SystemClock.elapsedRealtime()
        val enqueue = try {
            BridgeWorker.runRequested(applicationContext).result.get(ENQUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            "ok"
        } catch (e: TimeoutException) {
            Log.w(TAG, "el encolado del pedido no termino en $ENQUEUE_TIMEOUT_MS ms")
            "tope"
        } catch (t: Throwable) {
            Log.e(TAG, "fallo el encolado del pedido", t)
            "error: ${t.javaClass.simpleName}"
        }
        val enqueueMs = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "pedido encolado: $enqueue en $enqueueMs ms")
        PushLog.received(
            applicationContext, receivedMs, message.priority, message.originalPriority, enqueue, enqueueMs,
        )
    }

    companion object {
        private const val TAG = "ShapeUpBridge"
        /**
         * Tope de la espera del encolado. Android le da a onMessageReceived unos 10 s antes de
         * considerarlo colgado; el encolado normal tarda milisegundos. 3 s deja margen de sobra.
         */
        const val ENQUEUE_TIMEOUT_MS = 3_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * /ingesta-sdk/{uid}/estado/dispositivo. Siempre con merge: la funcion de ShapeUp escribe
 * ultimoPushMs en el mismo documento y no hay que pisarselo.
 */
object DeviceToken {
    private const val TAG = "ShapeUpBridge"
    private const val PREFS = "bridge"
    private const val KEY_TOKEN = "tokenGuardado"
    private const val KEY_UID = "tokenUid"
    private const val KEY_MS = "tokenActualizadoMs"
    private val lock = Mutex()

    class Saved(val registered: Boolean, val updatedMs: Long)

    fun saved(context: Context): Saved {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Saved(p.getString(KEY_TOKEN, null) != null, p.getLong(KEY_MS, 0))
    }

    /** Al cerrar sesion: el proximo login tiene que volver a escribir el token. */
    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_TOKEN).remove(KEY_UID).remove(KEY_MS).apply()
    }

    /**
     * Escribe el token si no coincide con el ultimo que el servidor confirmo para este uid.
     * Nunca tira: un fallo aca no puede tumbar la corrida. Devuelve el error, o null.
     */
    suspend fun sync(context: Context, uid: String, known: String? = null): String? = lock.withLock {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val token = known ?: FirebaseMessaging.getInstance().token.await()
            if (token == prefs.getString(KEY_TOKEN, null) && uid == prefs.getString(KEY_UID, null)) {
                return@withLock null
            }
            val now = System.currentTimeMillis()
            val data = mapOf(
                "fcmToken" to token,
                "actualizadoMs" to now,
                "modelo" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "versionPuente" to BuildConfig.VERSION_NAME,
            )
            val task = FirebaseFirestore.getInstance()
                .collection("ingesta-sdk").document(uid)
                .collection("estado").document("dispositivo")
                .set(data, SetOptions.merge())
            // Solo se da por guardado lo que el servidor confirmo; si no, la proxima corrida reintenta.
            val confirmed = withTimeoutOrNull(Uploader.CONFIRM_TIMEOUT_MS) { task.await(); true }
            if (confirmed == null) return@withLock "estado/dispositivo en cola (sin confirmar)"
            prefs.edit().putString(KEY_TOKEN, token).putString(KEY_UID, uid).putLong(KEY_MS, now).apply()
            Log.i(TAG, "token FCM escrito en estado/dispositivo")
            null
        } catch (t: Throwable) {
            Log.e(TAG, "no se pudo escribir estado/dispositivo", t)
            "estado/dispositivo: ${t.javaClass.simpleName}: ${t.message}"
        }
    }
}

/**
 * El ultimo push. Su resultado es la primera corrida "pedido" posterior.
 *
 * P96: ademas de la hora, guarda la prioridad con que llego (y la que pidio el servidor) y como
 * termino el encolado. La corrida que genera lo copia a su fila de corridas.json, para no tener
 * que adivinar la proxima vez que un pedido tarde.
 */
object PushLog {
    private const val PREFS = "bridge"
    private const val KEY_MS = "ultimoPushRecibidoMs"
    private const val KEY_PRIORITY = "ultimoPushPrioridad"
    private const val KEY_ORIGINAL = "ultimoPushPrioridadOriginal"
    private const val KEY_ENQUEUE = "ultimoPushEncolado"
    private const val KEY_ENQUEUE_MS = "ultimoPushEncoladoMs"

    class Record(
        val receivedMs: Long,
        val priority: String,
        val originalPriority: String,
        val enqueue: String,
        val enqueueMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("recibidoMs", receivedMs).put("prioridad", priority)
            .put("prioridadOriginal", originalPriority)
            .put("encolado", enqueue).put("encoladoMs", enqueueMs)
    }

    fun received(
        context: Context,
        receivedMs: Long,
        priority: Int,
        originalPriority: Int,
        enqueue: String,
        enqueueMs: Long,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_MS, receivedMs)
            .putString(KEY_PRIORITY, priorityName(priority))
            .putString(KEY_ORIGINAL, priorityName(originalPriority))
            .putString(KEY_ENQUEUE, enqueue)
            .putLong(KEY_ENQUEUE_MS, enqueueMs)
            .apply()
    }

    /** El ultimo push registrado, o null si no hubo ninguno desde P96. */
    fun last(context: Context): Record? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ms = p.getLong(KEY_MS, 0)
        val priority = p.getString(KEY_PRIORITY, null) ?: return null
        if (ms == 0L) return null
        return Record(
            ms, priority, p.getString(KEY_ORIGINAL, null) ?: "?",
            p.getString(KEY_ENQUEUE, null) ?: "?", p.getLong(KEY_ENQUEUE_MS, -1),
        )
    }

    private fun priorityName(p: Int) = when (p) {
        RemoteMessage.PRIORITY_HIGH -> "alta"
        RemoteMessage.PRIORITY_NORMAL -> "normal"
        else -> "desconocida($p)"
    }

    fun lastMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_MS, 0)
}
