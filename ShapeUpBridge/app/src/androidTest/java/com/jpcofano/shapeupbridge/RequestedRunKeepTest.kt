package com.jpcofano.shapeupbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P90, prueba 3 del lado del puente: pedidos repetidos mientras hay uno esperando o corriendo
 * producen una sola corrida. Prueba la politica KEEP de WorkManager llamando a runRequested
 * como lo hace onMessageReceived; no pasa por FCM ni por la funcion de ShapeUp.
 *
 * Hace una corrida real (lee Samsung Health y escribe estado/puente con origen 'pedido'), asi
 * que necesita la sesion iniciada en el puente. Se corre con adb am instrument, no con
 * connectedAndroidTest, que desinstala la app al terminar.
 */
@RunWith(AndroidJUnit4::class)
class RequestedRunKeepTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workManager = WorkManager.getInstance(context)

    // Mismo nombre que BridgeWorker.REQUESTED, que es privado.
    private fun active(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork("puente-pedido").get().filter { !it.state.isFinished }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("no se cumplio en $timeoutMs ms", System.currentTimeMillis() < end)
            Thread.sleep(100)
        }
    }

    @Test
    fun pedidosRepetidosCorrenUnaSolaVez() {
        assertNotNull("hace falta la sesion iniciada en el puente", FirebaseAuth.getInstance().currentUser)
        waitFor(120_000) { active().isEmpty() }
        val start = System.currentTimeMillis()

        // Dos pedidos seguidos: el segundo llega con el primero todavia encolado.
        BridgeWorker.runRequested(context)
        BridgeWorker.runRequested(context)
        assertEquals("pedidos encolados", 1, active().size)
        val id = active().single().id

        // Un tercero con el primero ya corriendo.
        waitFor(60_000) { active().singleOrNull()?.state == WorkInfo.State.RUNNING }
        BridgeWorker.runRequested(context)
        assertEquals("pedidos con uno corriendo", 1, active().size)
        assertEquals("sigue siendo el primero", id, active().single().id)

        waitFor(120_000) { active().isEmpty() }
        val runs = RunHistory.all(context).filter { it.origin == Origin.REQUESTED.value && it.endedMs >= start }
        assertEquals("corridas pedidas", 1, runs.size)
    }
}
