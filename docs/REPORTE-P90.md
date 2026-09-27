# Reporte P90 — El puente escucha

Estado: **hecho y funcionando; pendientes la prueba de Doze real y tocar el botón de batería.** Lo probado está medido y dice
por qué vía se disparó y con qué precisión. Lo pendiente está marcado `PENDIENTE` y no se
rellenó con estimaciones.

Fecha: 27/09/2026. Teléfono: Galaxy S25+ (SM-S936U1), Android 16, puente 1.0 debug.
Función: `pedirCorridaAlPuente` (P89), southamerica-east1, disparada al escribir
`ingesta-sdk/{uid}/estado/pedido`.

## Qué entró

- `firebase-messaging` y `PushService` (`FirebaseMessagingService`).
- `onNewToken` y cada inicio de sesión y cada corrida escriben `estado/dispositivo`
  (`fcmToken`, `actualizadoMs`, `modelo`, `versionPuente`) con merge, sin pisar
  `ultimoPushMs`. Solo se da por guardado lo que el servidor confirmó. Sin sesión no se escribe.
- `onMessageReceived` encola el mismo `BridgeWorker` como `OneTimeWorkRequest` expedited
  (`RUN_AS_NON_EXPEDITED_WORK_REQUEST` si no hay cuota), trabajo único `puente-pedido` con
  `KEEP`, y la corrida escribe `estado/puente` con `origen: 'pedido'`. Sin notificación
  (salvo Android ≤ 11, donde WorkManager la exige para expedited).
- Mutex en `BridgeRun`: una corrida por vez en el proceso, para que push y periódica no
  guarden el archivo de hashes a la vez.
- Pantalla: líneas **Token registrado**, **Último push recibido** (con el resultado de la
  corrida que disparó) y **Batería**, y botón para pedir la exclusión de optimización.
- README: app detenida, batería de Samsung, permiso de notificaciones, entrega tardía.
- La periódica de 6 h no se tocó.

## Cómo se midió

Hay tres relojes: el del **teléfono** (logcat, historial del puente), el de los **servidores**
(log de la función, `sentTime` de FCM) y el del **dispositivo que aprieta el botón**
(`pedidoMs`). Cuando una medición cruza relojes se dice; teléfono y servidores están
sincronizados por red y la diferencia esperable es de decenas de milisegundos. El reloj de la
PC está corrido (un `pedidoMs` salió posterior al `sentTime` del mismo push), así que los
`pedidoMs` de pedidos hechos desde la PC no se usan para medir.

## Pruebas

### 1 — Punta a punta ✅
Vía: **botón real**, desde ShapeUp en el mismo teléfono. Teléfono despierto.

| Tramo | Tiempo | Reloj |
|---|---|---|
| Botón (`pedidoMs`) → arranca la función | 1,12 s | teléfono → servidor |
| Arranque en frío de la instancia | 1,4 s (incluido en el siguiente) | servidor |
| Arranca la función → FCM acepta (`sentTime`) | 2,19 s | servidor |
| FCM → push recibido | 0,20 s | servidor → teléfono |
| Push → arranca el worker | 0,04 s | teléfono |
| Corrida + `estado/puente` confirmado | 2,79 s | teléfono |
| **Total botón → fin de corrida** | **6,34 s** | **teléfono en las dos puntas: preciso al ms** |

Resultado: 36 leídos, 0 errores, `estado/puente` con `origen: 'pedido'` confirmado.

### 2a — Doze forzado ✅
Vía: **botón real**, desde la web en la PC. Doze profundo forzado con
`dumpsys deviceidle force-idle`, pantalla apagada, sin cargar; `deep: IDLE` antes y después.

| Tramo | Tiempo | Reloj |
|---|---|---|
| Arranca la función → FCM acepta | 0,43 s (instancia caliente) | servidor |
| FCM → push recibido | 0,22 s | servidor → teléfono |
| Push → arranca el worker | 0,03 s | teléfono |
| Corrida + `estado/puente` confirmado | 2,37 s | teléfono |
| **Arranca la función → fin de corrida** | **3,05 s** | servidor → teléfono |

Botón → función no se pudo medir (reloj de la PC). Con el 1,12 s de la prueba 1, el punta a
punta con instancia caliente se **estima** en ~4,2 s; es estimación, no medición.

### 2b — Doze real — **PENDIENTE**
Procedimiento abajo.

### 3 — Dos pedidos seguidos ✅ (en dos partes, ninguna es la cadena entera)

**3a, lado ShapeUp.** Vía: **botón real**, dos veces (sin querer) con 16 s de diferencia. La
función mandó el primer push y descartó el segundo (`pedido ignorado: muy-seguido`). Una sola
corrida, pero por el freno de P89, no por el puente.

**3b, lado puente: política `KEEP`.** Vía: **test instrumentado**
[RequestedRunKeepTest](../ShapeUpBridge/app/src/androidTest/java/com/jpcofano/shapeupbridge/RequestedRunKeepTest.kt),
corrido en el teléfono con `adb shell am instrument` (no `connectedAndroidTest`, que
desinstala la app al terminar). Llama a `BridgeWorker.runRequested` como lo hace
`onMessageReceived`: dos veces seguidas con el pedido encolado y una tercera con el pedido ya
corriendo. Resultado: **un solo trabajo** (el mismo id las tres veces) y **una sola corrida
pedida** (37 leídos, 1 subido, 0 errores; 2,85 s). `OK (1 test)`.

**No es equivalente a dos pushes reales**: prueba la política de WorkManager, no FCM ni
`onMessageReceived` bajo dos entregas. A través de `estado/pedido` no se puede ejercitar (ni
con el botón ni como administrador), porque toda escritura ahí pasa por la función y el segundo
pedido cae en `muy-seguido`. Además de `KEEP`, el mutex de `BridgeRun` impide que dos corridas
se encimen aunque lleguen por caminos distintos: son dos defensas independientes.

El test hace una corrida real (escribe `estado/puente` con `origen: 'pedido'`) y necesita la
sesión iniciada:
```
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.jpcofano.shapeupbridge.RequestedRunKeepTest \
  com.jpcofano.shapeupbridge.test/androidx.test.runner.AndroidJUnitRunner
```

### 4 — Sin conexión ✅
Vía: **botón real**, desde la PC, con el teléfono en modo avión.

| Tramo | Tiempo | Reloj |
|---|---|---|
| FCM acepta → push recibido | 234,9 s | servidor → teléfono |
| Push → fin de corrida | 4,1 s (corrida 1,4 s; el resto, esperando red) | teléfono |

Los 234,9 s son lo que duró el modo avión; no se registró el instante exacto en que se sacó.
El push quedó en FCM y se entregó al volver la red. Una sola corrida pedida, sin
periódica encimada, `estado/puente` confirmado. Estado consistente.

### 5 — Token cambiado a la fuerza ✅
Vía: **botón real**, desde la PC. `pm clear` por adb y nuevo inicio de sesión.

- Sin sesión, `onNewToken` llegó y no escribió nada (correcto).
- Al entrar: token nuevo (`fK7ODKf7…`, antes `cMG8RVbT…`) escrito y confirmado en 0,53 s
  (reloj del teléfono).
- El push siguiente llegó al token nuevo; corrida pedida sin errores (15,4 s, ventana de 90
  días porque el borrado reinicia la ventana inicial).

Efecto colateral esperado, de PU3: la primera periódica tras el borrado subió 113 registros y
53 escrituras no se confirmaron en 15 s; quedaron en cola, la corrida pidió reintento y el
reintento terminó bien (36 leídos, ventana de 14 días).

### 6 — App detenida a la fuerza ✅
Vía: **botón real**, desde la PC. `am force-stop` por adb con el puente quieto.

| Momento | Qué pasó | Reloj |
|---|---|---|
| Detenida | `stopped=true`, sin proceso, **0 trabajos** en JobScheduler | teléfono |
| Push enviado | llegó al teléfono 70 ms después y Android lo rechazó (`GCM: broadcast intent callback: result=CANCELLED`) | servidor → teléfono |
| Se abre la app | `WM-ForceStopRunnable: Application was force-stopped, rescheduling`; 1 trabajo registrado, próxima periódica en ~5 h 58 min | teléfono |
| +81 s de abrirla | **el push rechazado se entregó** y disparó una corrida pedida que terminó bien (36 leídos, 0 errores) | teléfono |

Ni push ni periódica mientras está detenida; al abrir se reagenda todo solo. La entrega tardía
queda como comportamiento conocido (README).

Nota de método: un primer `force-stop` cayó mientras corría un trabajo y JobScheduler relanzó
el puente a los 77 ms, borrando la marca de detenida. Para esta prueba hay que detener el
puente quieto.

### Botón de batería — verificado por logs; tocarlo **PENDIENTE**
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: granted=true`.
- El intent del botón resuelve a `com.android.settings/.fuelgauge.RequestIgnoreBatteryOptimizations`;
  el de respaldo, a `Settings$AppBatteryUsageActivity`.
- La exclusión ya estaba concedida (`deviceidle whitelist: user,com.jpcofano.shapeupbridge`,
  `RUN_ANY_IN_BACKGROUND: allow`) y la línea **Batería** dice "sin optimizacion (concedido)":
  coinciden. Por eso el botón está oculto.
- Falta: sacar la exclusión (`adb shell dumpsys deviceidle whitelist -com.jpcofano.shapeupbridge`),
  ver que la línea cambie y aparezca el botón, tocarlo, aceptar el diálogo de Samsung y ver
  que vuelva a "concedido".

## Cómo correr la 2b (Doze real)

Va de noche: Doze profundo llega tras un rato largo quieto y con la pantalla apagada, y
Samsung suma sus propias restricciones.

**Antes de dormir**
1. Teléfono **desenchufado**: cargando no entra en Doze. Batería por encima del nivel bajo (la
   periódica exige batería no baja).
2. Opcional, con adb: `adb shell dumpsys deviceidle get deep` debería decir `ACTIVE`. **No
   forzar nada.**
3. Pantalla apagada, teléfono quieto sobre una superficie. No tocarlo hasta la mañana.

**A la mañana, sin desbloquear el teléfono** (desbloquearlo lo saca de Doze)
4. Desde la web de ShapeUp en la PC (https://shapeup-41e74.web.app), apretar el botón de la
   tarjeta Puente Samsung. Anotar la hora. Esperar un minuto.
5. Recién ahí desbloquear y conectar adb. **No enchufarlo antes de juntar los datos**: al
   cargar se reinicia el historial de batería.

**Juntar la evidencia**
```
adb shell dumpsys batterystats --history > bs.txt        # buscar device_idle=full / off con hora
adb shell run-as com.jpcofano.shapeupbridge cat files/corridas.json
adb logcat -d -v epoch -s ShapeUpBridge:V WM-WorkerWrapper:V
firebase functions:log --project shapeup-41e74 --only pedirCorridaAlPuente -n 20
```

**Qué tiene que dar**
- En `bs.txt`, `device_idle=full` vigente a la hora del push (el teléfono estaba en Doze
  profundo real).
- En logcat, `push recibido` y `corrida pedido` sin errores; medir función → fin de corrida
  como en la 2a.
- En `corridas.json`, corridas `segundo-plano` durante la noche separadas ~6 h (la periódica
  entró en Doze, en sus ventanas de mantenimiento).

## Para P89 / P91 (no son del puente)

- **P91 — la guarda de "pedido de más de 5 minutos" usa el reloj del cliente.** Compara contra
  `pedidoMs`, que es la hora del dispositivo que apretó el botón. Si ese reloj está atrasado
  cinco minutos o más, **ningún pedido se despacha nunca** y nada lo delata: la app parece
  andar y no llega nada. Desde el puente se vería como "Último push recibido" que no avanza
  mientras ShapeUp dice que pidió. La función tiene que usar la hora de escritura del
  documento, que conoce por su cuenta, y no una que manda el cliente. En estas pruebas ya se
  vio un reloj de PC corrido (un `pedidoMs` posterior al `sentTime` de su propio push).

- Cuando la función descarta un pedido por `muy-seguido`, la tarjeta lo sigue esperando y
  termina en "El reloj no contestó a tiempo", aunque el reloj contestó al pedido anterior.
- `pedidoMs` sale del reloj del dispositivo que aprieta el botón; desde una PC con la hora
  corrida, cualquier cálculo de ShapeUp con ese valor da mal.
- Con la app detenida, el pedido puede terminar respondiéndose minutos después de abrir el
  puente, cuando ShapeUp ya lo dio por vencido.

## Dónde se apartó de P90

- Mutex en `BridgeRun` (aprobado).
- "Token guardado" se compara contra el último confirmado por el servidor, guardado en el
  teléfono, sin leer Firestore en cada corrida (aprobado; el caso del documento borrado a mano
  está en el README).
- La prueba 6 original esperaba que la periódica entrara con la app detenida; se corrigió.
- El permiso de notificaciones se sigue pidiendo al iniciar sesión, para los avisos de error
  del worker (PU3), no para el push.
- El log del push registra los datos del mensaje (`tipo`, `origen`, `pedidoMs`) y `sentTime`,
  para poder medir sin leer Firestore.
