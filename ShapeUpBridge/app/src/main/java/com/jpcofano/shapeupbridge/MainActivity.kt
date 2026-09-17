package com.jpcofano.shapeupbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.jpcofano.shapeupbridge.databinding.ActivityMainBinding
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.request.DataTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Puente. "Leer" vuelca a archivo (PU1); "Leer y subir" hace la misma lectura y ademas sube cada
 * registro crudo a Firestore (PU2). Sin normalizacion: el alcance esta en
 * docs/prompts/P88prima-poc-data-sdk.md y docs/prompts/pu2-subida-firestore.md.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: HealthDataStore
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val credentialManager by lazy { CredentialManager.create(this) }
    private var outputFile: File? = null

    private val permissions = BridgeRun.PERMISSIONS

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            say("permiso de notificaciones: ${if (granted) "concedido" else "denegado"}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left + 32, bars.top + 32, bars.right + 32, bars.bottom + 32)
            insets
        }

        store = HealthDataService.getStore(applicationContext)

        binding.readButton.setOnClickListener { run(upload = false) }
        binding.uploadButton.setOnClickListener { run(upload = true) }
        binding.shareButton.setOnClickListener { share() }
        binding.signInButton.setOnClickListener { signIn() }
        binding.signOutButton.setOnClickListener { signOut() }
        binding.runNowButton.setOnClickListener {
            BridgeWorker.runNow(applicationContext)
            say("corrida en segundo plano encolada: arranca en 60 s")
        }

        // Firebase persiste la sesion entre aperturas: si hay usuario aca, no hubo login nuevo.
        val restored = auth.currentUser
        say(if (restored != null) "sesion restaurada al abrir: ${restored.email}" else "sin sesion al abrir")
        if (restored != null) BridgeWorker.schedule(applicationContext)
        renderAuth()

        say("zona horaria del telefono: ${ZoneId.systemDefault()}")
        say("dia objetivo local (Leer): ${Target.DAY_START} .. ${Target.DAY_END}")
        say("ventana de la proxima subida: ${BridgeRun.windowDays(this)} dias")
        checkPermissionsAtStartup()
    }

    /**
     * Friccion operativa: si el permiso sobrevive al cierre de la app se ve aca, antes de
     * pedir nada. El contador de arranques distingue "primera vez" de "se perdio".
     */
    private fun checkPermissionsAtStartup() {
        val prefs = getSharedPreferences("bridge", MODE_PRIVATE)
        val launches = prefs.getInt("launches", 0) + 1
        prefs.edit().putInt("launches", launches).apply()
        say("arranque numero $launches de la app")
        lifecycleScope.launch {
            try {
                val granted = store.getGrantedPermissions(permissions)
                startupGranted = granted.map { it.dataType.name }.sorted()
                say("permisos ya concedidos al arrancar: ${startupGranted.ifEmpty { listOf("(ninguno)") }}")
            } catch (t: Throwable) {
                say("no se pudo consultar permisos al arrancar: ${t.javaClass.simpleName}: ${t.message}")
                if (t is ResolvablePlatformException && t.hasResolution) {
                    say("Samsung Health pide resolucion; abriendola")
                    t.resolve(this@MainActivity)
                }
            }
        }
    }

    private var startupGranted: List<String> = emptyList()

    private fun renderAuth() {
        val user = auth.currentUser
        binding.authStatus.text = user?.email ?: getString(R.string.signed_out)
        binding.signInButton.visibility = if (user == null) View.VISIBLE else View.GONE
        binding.signOutButton.visibility = if (user == null) View.GONE else View.VISIBLE
        binding.uploadButton.isEnabled = user != null
        binding.runNowButton.isEnabled = user != null
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
    }

    /** Las ultimas corridas guardadas localmente, manuales y de segundo plano. */
    private fun renderHistory() {
        val runs = RunHistory.all(this)
        if (runs.isEmpty()) {
            binding.historyView.text = getString(R.string.no_runs)
            return
        }
        val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
        binding.historyView.text = runs.joinToString("\n") { r ->
            buildString {
                append("${fmt.format(Date(r.endedMs))}  ${r.origin}  ${r.windowDays}d  ")
                append("leidos ${r.read} subidos ${r.uploaded} sinCambios ${r.unchanged} ")
                append("omitidos ${r.skipped} errores ${r.errors}  ${r.durationMs} ms")
                if (r.queued > 0) append("  (en cola ${r.queued})")
                if (r.message != null) append("\n    ${r.message}")
                if (r.note != null) append("\n    ${r.note}")
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Credential Manager -> id token de Google -> Firebase Auth. */
    private fun signIn() {
        binding.signInButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val option = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(getString(R.string.default_web_client_id))
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(option)
                    .build()
                val credential = credentialManager.getCredential(this@MainActivity, request).credential
                if (credential !is CustomCredential ||
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    say("login: credencial inesperada (${credential.type})")
                    return@launch
                }
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(google.idToken, null)
                val user = auth.signInWithCredential(firebaseCredential).await().user
                say("login ok: ${user?.email}")
                if (user != null) {
                    BridgeWorker.schedule(applicationContext)
                    say("trabajo periodico agendado (cada 6 h)")
                    requestNotificationPermission()
                }
            } catch (t: Throwable) {
                say("login fallo: ${t.javaClass.simpleName}: ${t.message}")
                Log.e(TAG, "login", t)
            } finally {
                binding.signInButton.isEnabled = true
                renderAuth()
            }
        }
    }

    private fun signOut() {
        lifecycleScope.launch {
            BridgeWorker.cancel(applicationContext)
            auth.signOut()
            runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
            say("sesion cerrada; trabajo periodico cancelado")
            renderAuth()
        }
    }

    private fun run(upload: Boolean) {
        // El uid sale siempre de la sesion de Firebase, nunca del codigo.
        val userUid = auth.currentUser?.uid
        if (upload && userUid == null) {
            say("no hay sesion: entrar con Google antes de subir")
            return
        }
        binding.readButton.isEnabled = false
        binding.uploadButton.isEnabled = false
        binding.runNowButton.isEnabled = false
        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            try {
                val permissionsJson = JSONObject()
                permissionsJson.put(
                    "requested",
                    JSONArray(permissions.map { "${it.dataType.name}:${it.accessType.name}" }.sorted())
                )
                permissionsJson.put("grantedAtStartup", JSONArray(startupGranted))

                var granted = store.getGrantedPermissions(permissions)
                permissionsJson.put(
                    "grantedBeforeRequest",
                    JSONArray(granted.map { it.dataType.name }.sorted())
                )
                if (!granted.containsAll(permissions)) {
                    say("faltan permisos; pidiendolos")
                    permissionsJson.put("popupShown", true)
                    granted = store.requestPermissions(permissions, this@MainActivity)
                } else {
                    say("permisos ya concedidos; no hizo falta el popup")
                    permissionsJson.put("popupShown", false)
                }
                permissionsJson.put(
                    "grantedAfterRequest",
                    JSONArray(granted.map { it.dataType.name }.sorted())
                )
                if (!granted.containsAll(permissions)) {
                    say("ATENCION: faltan permisos despues de pedirlos; se lee igual y queda registrado")
                }

                val runInfo = JSONObject()
                    .put("launchNumber", getSharedPreferences("bridge", MODE_PRIVATE).getInt("launches", 0))
                    .put("timeZone", ZoneId.systemDefault().id)
                    .put("startedAt", instant(Instant.ofEpochMilli(started)))

                if (upload && userUid != null) {
                    runAndUpload(userUid)
                    return@launch
                }

                say("leyendo ejercicio del 14/09 ...")
                val reader = BridgeReader(store)
                val result = withContext(Dispatchers.Default) {
                    buildDump(applicationContext, reader, permissionsJson, runInfo, ReadRange.PU1)
                }

                val elapsed = System.currentTimeMillis() - started
                result.json.getJSONObject("timings").put("totalMs", elapsed)

                val file = withContext(Dispatchers.IO) { BridgeRun.writeDumpFile(applicationContext, result.json) }
                outputFile = file
                binding.shareButton.isEnabled = true

                report(result, file, elapsed)
            } catch (t: Throwable) {
                say("FALLO: ${t.javaClass.name}: ${t.message}")
                Log.e(TAG, "fallo la corrida", t)
                if (t is ResolvablePlatformException && t.hasResolution) {
                    say("Samsung Health ofrece resolucion; abriendola")
                    t.resolve(this@MainActivity)
                }
            } finally {
                binding.readButton.isEnabled = true
                renderAuth()
                renderHistory()
            }
        }
    }

    /** "Leer y subir": la corrida compartida con el worker, con origen manual. */
    private suspend fun runAndUpload(userUid: String) {
        val outcome = BridgeRun.execute(this, Origin.MANUAL, userUid) { say(it) }
        val dump = outcome.dump
        val file = outcome.file
        if (dump != null && file != null) {
            outputFile = file
            binding.shareButton.isEnabled = true
            report(dump, file, outcome.summary.durationMs)
        }
        for (i in 0 until outcome.readerErrors.length()) {
            val e = outcome.readerErrors.getJSONObject(i)
            say("error de lectura: ${e.optString("where")}: ${e.optString("type")}: ${e.optString("message")}")
        }
        val failure = outcome.failure
        if (failure is ResolvablePlatformException && failure.hasResolution) {
            say("Samsung Health ofrece resolucion; abriendola")
            failure.resolve(this)
        }

        val s = outcome.summary
        val r = outcome.upload
        say("")
        say("--- subida a Firestore (ventana ${s.windowDays} dias) ---")
        say("leidos: ${s.read}  subidos: ${s.uploaded}  sinCambios: ${s.unchanged}  omitidos: ${s.skipped}  errores: ${s.errors}")
        if (r != null) {
            r.split.forEach { (id, n) -> say("  partido en $n partes: $id") }
            r.skippedTooManyParts.forEach { say("  omitido (mas de ${Uploader.MAX_PARTS} partes): $it") }
            r.sanitizedIds.forEach { say("  id con barra reemplazada por guion bajo: $it") }
            if (r.queued > 0) say("En cola: se sube cuando haya señal (${r.queued} registros)")
            if (r.permissionDenied) {
                say("PERMISSION_DENIED: lo mas probable es que la regla de /ingesta-sdk no este desplegada")
            }
        }
        s.message?.let { say("  primer error: $it") }
        s.note?.let { say("  $it") }
        say("duracion: ${s.durationMs} ms")

        val uploader = outcome.uploader ?: return
        if (r == null || r.queued > 0 || r.failed > 0) {
            say("verificacion omitida: la subida no quedo confirmada por el servidor")
            return
        }
        val target = dump?.records?.firstOrNull {
            it.dataType == DataTypes.EXERCISE.name && it.uid == dump.targetUid
        }
        if (target == null) {
            say("verificacion omitida: la sesion de referencia no esta en la ventana")
            return
        }
        say("")
        try {
            val v = uploader.verify(target)
            say("--- verificacion, leido del servidor: ${v.docIds.joinToString()} ---")
            for (c in v.checks) {
                say("${if (c.ok) "✓" else "✗"} ${c.label}: ${c.actual} (esperado ${c.expected})")
            }
        } catch (t: Throwable) {
            say("verificacion fallo: ${t.javaClass.simpleName}: ${t.message}")
            Log.e(TAG, "verificacion", t)
        }
    }

    private fun report(result: DumpResult, file: File, elapsedMs: Long) {
        val json = result.json
        say("")
        say("--- resultado ---")
        val exercise = json.getJSONObject("exercise")
        say("sesiones encontradas: ${exercise.getInt("sessionCount")}")
        val summaries = exercise.getJSONArray("summaries")
        for (i in 0 until summaries.length()) {
            val s = summaries.getJSONObject(i)
            if (summaries.length() > 12 && s.optString("uid") != result.targetUid) continue
            val start = s.getJSONObject("startTime").getString("iso")
            say(
                "  $start  ${s.opt("exerciseType")}  " +
                    "titulo=${s.opt("customTitle")}  log=${s.opt("logSize")}  " +
                    "conFC=${s.opt("logWithHeartRate")}  auto=${s.opt("autoDetected")}"
            )
        }
        val target = json.getJSONObject("targetSession")
        say("")
        if (target.getBoolean("found")) {
            val stats = target.getJSONObject("stats")
            say("sesion ShapeUp encontrada: uid=${target.opt("uid")}")
            say("  customTitle=${target.opt("customTitle")}  tipo=${target.opt("exerciseType")}")
            say("  log=${stats.opt("logSize")}  conFC=${stats.opt("logWithHeartRate")}")
            say("  media=${stats.opt("meanHeartRate")}  max=${stats.opt("maxHeartRate")}  min=${stats.opt("minHeartRate")}")
            say("  verdad de campo: 4133 / 4112 / 118,21 / 174 / 83")
            say("  hueco maximo=${stats.opt("maxGapMs")} ms  ultimo hueco=${stats.opt("lastGapMs")} ms")
        } else {
            say("sesion ShapeUp NO encontrada para epoch ${Target.SESSION_START_EPOCH_MS}")
        }
        val body = json.getJSONObject("bodyComposition")
        say("")
        val bodyRange = json.getJSONObject("ranges").getJSONObject("bodyComposition")
        say("composicion corporal: ${body.getInt("dataPointCount")} registros de ${bodyRange.optString("localFrom")} a ${bodyRange.optString("localTo")}")
        val errors = json.getJSONArray("errors")
        if (errors.length() > 0) say("errores registrados: ${errors.length()} (ver JSON)")
        say("")
        say("archivo: ${file.absolutePath}")
        say("peso total: ${file.length()} bytes  |  curva sola: ${result.curveBytes} bytes")
        say("tiempo total: $elapsedMs ms")
        say("")
        say("tocar COMPARTIR para sacarlo del telefono")
        Log.i(TAG, json.toString())
    }

    private fun share() {
        val file = outputFile ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        binding.logView.append(line + "\n")
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        const val TAG = "ShapeUpBridge"
    }
}
