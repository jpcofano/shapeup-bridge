package com.jpcofano.shapeupbridge

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

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

    private val permissions = setOf(
        Permission.of(DataTypes.EXERCISE, AccessType.READ),
        Permission.of(DataTypes.HEART_RATE, AccessType.READ),
        Permission.of(DataTypes.BODY_COMPOSITION, AccessType.READ),
    )

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

        // Firebase persiste la sesion entre aperturas: si hay usuario aca, no hubo login nuevo.
        val restored = auth.currentUser
        say(if (restored != null) "sesion restaurada al abrir: ${restored.email}" else "sin sesion al abrir")
        renderAuth()

        say("zona horaria del telefono: ${ZoneId.systemDefault()}")
        say("dia objetivo local: ${Target.DAY_START} .. ${Target.DAY_END}")
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
            auth.signOut()
            runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
            say("sesion cerrada")
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

                say("leyendo ejercicio del 14/09 ...")
                val reader = BridgeReader(store)
                val result = withContext(Dispatchers.Default) {
                    buildDump(applicationContext, reader, permissionsJson, runInfo)
                }

                val elapsed = System.currentTimeMillis() - started
                result.json.getJSONObject("timings").put("totalMs", elapsed)

                val file = withContext(Dispatchers.IO) { write(result.json) }
                outputFile = file
                binding.shareButton.isEnabled = true

                report(result, file, elapsed)

                if (upload && userUid != null) upload(result, userUid)
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
            }
        }
    }

    private suspend fun upload(result: DumpResult, userUid: String) {
        say("")
        say("--- subida a Firestore ---")
        say("registros leidos: ${result.records.size}")
        val uploader = Uploader(FirebaseFirestore.getInstance(), userUid)
        val r = uploader.upload(result.records, BuildConfig.VERSION_NAME)
        say("subidos: ${r.uploaded}  omitidos por tamano: ${r.skippedBySize.size}  errores: ${r.failed}")
        r.skippedBySize.forEach { say("  omitido por tamano: $it") }
        r.sanitizedIds.forEach { say("  id con barra reemplazada por guion bajo: $it") }
        r.errorMessages.forEach { say("  error: $it") }
        if (r.queued > 0) say("En cola: se sube cuando haya señal (${r.queued} registros)")
        if (r.permissionDenied) {
            say("PERMISSION_DENIED: lo mas probable es que la regla de /ingesta-sdk (PU2a) no este desplegada")
        }
        say("tiempo de subida: ${r.elapsedMs} ms")

        if (r.queued > 0 || r.failed > 0) {
            say("verificacion omitida: la subida no quedo confirmada por el servidor")
            return
        }
        val target = result.records.firstOrNull {
            it.dataType == DataTypes.EXERCISE.name && it.uid == result.targetUid
        }
        if (target == null) {
            say("verificacion omitida: la sesion de referencia no aparecio en la lectura")
            return
        }
        say("")
        say("--- verificacion: ${Uploader.docIdOf(target)} leido del servidor ---")
        try {
            for (c in uploader.verify(target)) {
                say("${if (c.ok) "✓" else "✗"} ${c.label}: ${c.actual} (esperado ${c.expected})")
            }
        } catch (t: Throwable) {
            say("verificacion fallo: ${t.javaClass.simpleName}: ${t.message}")
            Log.e(TAG, "verificacion", t)
        }
    }

    private fun write(json: JSONObject): File {
        val dir = File(filesDir, "salidas").apply { mkdirs() }
        val file = File(dir, "volcado-m1.json")
        file.writeText(json.toString(2))
        return file
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
        say("composicion corporal: ${body.getInt("dataPointCount")} registros del 05/09 al 15/09")
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
