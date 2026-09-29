package com.jpcofano.shapeupbridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

/** La misma corrida que "Leer y subir", sin la app abierta. */
class BridgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Notifier.show(applicationContext, "ShapeUp Bridge: entrá a la app para volver a iniciar sesión")
            // success y no retry: sin sesion, reintentar no arregla nada.
            return Result.success()
        }
        val origin = if (inputData.getString(KEY_ORIGIN) == Origin.REQUESTED.value)
            Origin.REQUESTED else Origin.BACKGROUND
        val outcome = BridgeRun.execute(applicationContext, origin, user.uid)
        val s = outcome.summary
        Log.i(TAG, "corrida ${origin.value}: leidos=${s.read} subidos=${s.uploaded} sinCambios=${s.unchanged} omitidos=${s.skipped} errores=${s.errors} mensaje=${s.message}")

        if (s.errors > 0 || s.skipped > 0) {
            Notifier.show(
                applicationContext,
                "ShapeUp Bridge: ${s.errors} errores, ${s.skipped} omitidos " +
                    "(leídos ${s.read}, subidos ${s.uploaded})",
            )
        }
        // Solo un fallo de red se reintenta; lo demas ya quedo registrado en el estado.
        return if (outcome.networkFailure) Result.retry() else Result.success()
    }

    /**
     * Solo lo usa un pedido expedited en Android 11 o anterior, donde WorkManager lo corre como
     * servicio en primer plano y exige una notificacion. De Android 12 en adelante no se muestra.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUN, "Corrida pedida", NotificationManager.IMPORTANCE_MIN)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_RUN)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ShapeUp Bridge")
            .setContentText("Subiendo lo pedido por ShapeUp")
            .setOngoing(true)
            .build()
        return ForegroundInfo(FOREGROUND_ID, notification)
    }

    companion object {
        private const val TAG = "ShapeUpBridge"
        private const val PERIODIC = "puente-periodico"
        private const val NOW = "puente-ahora"
        private const val REQUESTED = "puente-pedido"
        private const val KEY_ORIGIN = "origen"
        private const val CHANNEL_RUN = "corrida-pedida"
        private const val FOREGROUND_ID = 900

        private fun constraints(batteryNotLow: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(batteryNotLow)
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BridgeWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints(batteryNotLow = true))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }

        /**
         * Para probar: una corrida con el mismo worker. Solo exige red. Arranca a los 60 s para
         * que la prueba alcance a cerrar la app y bloquear el telefono: si arrancara ya, correria
         * con la app en pantalla y no probaria el segundo plano.
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<BridgeWorker>()
                .setConstraints(constraints(batteryNotLow = false))
                .setInitialDelay(60, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * P90: la corrida que pide un push. Expedited para que entre aun en Doze; si se agoto la
         * cuota, corre como trabajo comun en vez de perderse. KEEP: un segundo push mientras la
         * primera espera o corre no encola otra.
         *
         * P96: devuelve la Operation. El encolado es asincrono (guarda en la base y recien despues
         * agenda en JobScheduler); quien llama desde un push tiene que esperarla, o la CPU se
         * duerme antes de que el job exista.
         */
        fun runRequested(context: Context): Operation {
            val request = OneTimeWorkRequestBuilder<BridgeWorker>()
                .setConstraints(constraints(batteryNotLow = false))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_ORIGIN to Origin.REQUESTED.value))
                .build()
            return WorkManager.getInstance(context)
                .enqueueUniqueWork(REQUESTED, ExistingWorkPolicy.KEEP, request)
        }

        fun requestedWork(context: Context) =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(REQUESTED)
    }
}

object Notifier {
    private const val CHANNEL = "puente"
    private var nextId = 1000

    fun show(context: Context, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("ShapeUpBridge", "sin permiso de notificaciones: $text")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Puente", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("ShapeUp Bridge")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(nextId++, notification)
        } catch (e: SecurityException) {
            Log.w("ShapeUpBridge", "notificacion rechazada", e)
        }
    }
}
