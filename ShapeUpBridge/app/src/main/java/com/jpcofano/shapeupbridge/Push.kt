package com.jpcofano.shapeupbridge

import android.content.Context
import android.os.Build
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
        Log.i(TAG, "push recibido: prioridad=${message.priority} original=${message.originalPriority} enviadoMs=${message.sentTime} datos=${message.data}")
        PushLog.received(applicationContext)
        BridgeWorker.runRequested(applicationContext)
    }

    companion object {
        private const val TAG = "ShapeUpBridge"
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

/** Cuando llego el ultimo push. Su resultado es la primera corrida "pedido" posterior. */
object PushLog {
    private const val PREFS = "bridge"
    private const val KEY_MS = "ultimoPushRecibidoMs"

    fun received(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_MS, System.currentTimeMillis()).apply()
    }

    fun lastMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_MS, 0)
}
