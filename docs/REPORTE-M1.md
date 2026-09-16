# Reporte M1 — PoC de lectura por la vía D (Samsung Health Data SDK)

Estado: **parcial — falta la corrida en el teléfono.** Todo lo que se puede verificar sin
desbloquear el dispositivo está medido y marcado como tal. Lo que depende de la lectura está
marcado `PENDIENTE` y no se rellenó con estimaciones.

---

## 1. Entorno de la corrida

| | Valor | Cómo se verificó |
|---|---|---|
| SDK | `samsung-health-data-api-1.1.0.aar` | nombre del artefacto en `app/libs/`; la app lo reconfirma en runtime con `SdkVersion.getVersionName()` y lo escribe en el JSON |
| Samsung Health en el teléfono | **7.00.6.011** (versionCode 7006011) | `adb shell dumpsys package com.sec.android.app.shealth` |
| Dispositivo | Samsung **SM-S936U1** | `adb devices -l` |
| Zona horaria del teléfono | `America/Argentina/Buenos_Aires` (UTC−3) | log de la app al arrancar |
| Android / compileSdk / minSdk | targetSdk 37, compileSdk 37, minSdk 29 | `app/build.gradle.kts` |
| AGP / Gradle / JDK | 9.3.2 / 9.5.0 / 21, Java 17 en `compileOptions` y `jvmTarget` | sin cambios respecto de lo que puso el asistente |

La zona horaria importa para el criterio de lectura: la sesión de referencia empieza
`2026-09-14T20:34:52.506Z`, que en UTC−3 es las **17:34 del 14/09 local**, de modo que el filtro
por día local completo (`2026-09-14T00:00` a `2026-09-15T00:00`) la cubre.

## 2. Cómo se resolvió la API del SDK

`sdk/docs/api-reference.html` y `sdk/docs/programming-guide.html` **no contienen documentación**:
son tres archivos de 274 bytes con un `<meta http-equiv="refresh">` que redirige a
`developer.samsung.com`. No hay referencia de API offline en el repositorio.

Para no inferir firmas se usaron dos fuentes, y se cruzaron:

1. **El `.aar` mismo**, que es autoritativo: se extrajo `classes.jar` y se volcaron las firmas
   con `javap`. De ahí salen todos los tipos y métodos que usa el PoC.
2. La guía online, que confirmó el flujo de permisos y el patrón
   `DataTypes.X.readDataRequestBuilder`.

Firmas efectivamente usadas, todas verificadas contra el binario:

```
HealthDataService.getStore(Context): HealthDataStore
HealthDataStore.getGrantedPermissions(Set<Permission>): Set<Permission>      // suspend
HealthDataStore.requestPermissions(Set<Permission>, Activity): Set<Permission> // suspend
HealthDataStore.readData(ReadDataRequest<T>): DataResponse<T>                // suspend
Permission.of(DataType, AccessType)
DataTypes.EXERCISE / HEART_RATE / BODY_COMPOSITION
DataType.ExerciseType.SESSIONS: Field<List<ExerciseSession>>
ExerciseSession.getLog(): List<ExerciseLog>?
ExerciseLog.getTimestamp(): Instant / .getHeartRate(): Float?
LocalTimeFilter.of(LocalDateTime, LocalDateTime)
ReadSourceFilter.fromDeviceType(DeviceGroup) / .fromLocalDevice() / .fromPlatform()
```

Dos diferencias con lo que se podría haber supuesto, y que habrían roto el build:

- `HealthDataPoint.getStartLocalDateTime()` y `getEndLocalDateTime()` son **funciones**, no
  propiedades: desde Kotlin no se acceden como `.startLocalDateTime`.
- El `.aar` **no trae POM**: sus dependencias de runtime no se resuelven solas y compilar no las
  detecta. Hay que declarar a mano tres:

  | Dependencia | Quién la usa | Versión elegida |
  |---|---|---|
  | `kotlinx-coroutines-android` | toda la API `suspend` | 1.9.0 |
  | `kotlin-parcelize-runtime` | los `Companion` de `ReadDataRequest`, `DataResponse`, `InsertDataRequest`, `UpdateDataRequest`, `DeviceResponse`, `SwimmingLog` implementan `kotlinx.parcelize.Parceler` | 2.2.10, igual al `kotlin-stdlib` que resuelve AGP 9.3.2 |
  | `com.google.code.gson:gson` | `ExerciseSession` y varias clases internas | 2.11.0 |

  Samsung no publica estas versiones en la guía ni en la página del ejemplo. Salen de listar las
  referencias externas del `classes.jar`. La única referencia que queda sin resolver es
  `androidx.databinding`: la usan solo `DataBinderMapperImpl` y dos clases anidadas en ella, y
  ninguna otra clase del SDK la toca.

## 3. Hallazgos de la API, verificables sin correr

Estos tres salen de la forma del SDK, no de los datos, así que ya son firmes.

**El `custom_id` no viaja por el SDK.** `DataType.ExerciseType` expone exactamente tres campos —
`EXERCISE_TYPE`, `CUSTOM_TITLE` y `SESSIONS`— y `ExerciseSession` no tiene ningún getter de id de
ejercicio custom. El `mq1mz4gd_gq` de la exportación manual **no tiene representación** por esta
vía. Lo más cercano es `HealthDataPoint.getClientDataId()`, que es el id que pone la app que
escribió el registro, no el id del ejercicio custom de Samsung. Si `custom_id` hace falta para la
clave canónica, la vía D no lo entrega. Si la clave es inicio + tipo + `appId`, como dice
`fuentes-y-reglas.md`, no hace falta.

**El nombre custom sí tiene por dónde viajar**: `ExerciseSession.getCustomTitle(): String?`. Que
traiga `"ShapeUp"` es cuestión de la corrida.

**Las autodetectadas se distinguen con un booleano explícito**, mucho mejor que inferirlo de un
`live_data_internal` vacío: `ExerciseSession.getAutoDetected(): Boolean?`. El volcado lo incluye
por sesión.

## 4. Sesiones del 14/09 encontradas

`PENDIENTE` — requiere la corrida.

| Inicio (UTC) | Tipo | `customTitle` | `autoDetected` | `log` | de los cuales con FC |
|---|---|---|---|---|---|

## 5. Estadísticas recalculadas contra la verdad de campo

`PENDIENTE` — requiere la corrida.

| | Verdad de campo | Medido por el SDK | Δ |
|---|---|---|---|
| Entradas en `log` | 4133 | | |
| Entradas con `heartRate` | 4112 | | |
| FC media | 118,21 | | |
| FC máxima | 174 | | |
| FC mínima | 83 | | |

Criterio de éxito: ≥ 4000 entradas con FC y las tres estadísticas dentro de ±1 bpm.

Las estadísticas se recalculan sobre la curva que entregó el SDK, no se toman de
`meanHeartRate`/`maxHeartRate` de la sesión. El volcado incluye ambas cosas por separado, para
poder contrastarlas.

**Control de interpolación**: la verdad de campo espera un salto de **17,48 s** entre las dos
últimas entradas del `log`. El volcado registra `lastGapMs`, `maxGapMs` y `medianGapMs`. Si el
último salto no aparece, algo está interpolando.

## 6. Fricción operativa

### Corrida 1 — fallida, 2026-09-16 11:24Z (`salidas/volcado-m1.json`)

Las seis lecturas (ejercicio, cuatro sondas por fuente y composición corporal) fallaron con
`java.lang.NoClassDefFoundError: com.samsung.android.sdk.health.data.request.ReadDataRequest`,
en `totalMs: 4`. **No llegó a leer Samsung Health**, así que no dice nada sobre los datos.

Causa: `ReadDataRequest` **sí estaba** en el APK. Lo que faltaba era `kotlinx.parcelize.Parceler`,
que implementa su `Companion`: al inicializar la clase falla la carga del `Companion`, y ART lo
reporta como `NoClassDefFoundError` de la clase exterior. Los permisos funcionaron porque
`Permission` no usa parcelize. Se corrigió agregando `kotlin-parcelize-runtime`, y de paso Gson,
que habría fallado en la lectura siguiente. Se verificó sobre el APK con `dexdump` que las clases
quedaron adentro.

Lo que esa corrida sí prueba:

- **Que los permisos sobrevivan al reinicio de la app queda sin probar.** El JSON es del
  arranque 2 y `grantedAtStartup` vino **vacío**: al abrir la app por segunda vez no había ningún
  permiso concedido. El arranque 1 fue la prueba de instalación, en la que nunca se tocó Leer. Sin
  embargo, `grantedBeforeRequest` trae los tres permisos y `popupShown: false`. La lectura más
  consistente es que en el arranque 2 se tocó Leer dos veces: la primera mostró el diálogo y se
  concedieron los permisos, y la segunda sobrescribió `volcado-m1.json`, porque el archivo se
  reescribe en cada lectura. Eso prueba que el permiso persiste **dentro del mismo proceso**, no
  entre arranques. La otra explicación, que la consulta de arranque no hubiera terminado al tocar
  Leer, es poco probable: esa consulta tarda milisegundos.
  Para probarlo: cerrar la app del todo, reabrirla y verificar que `grantedAtStartup` traiga los
  tres permisos.
- **El modo desarrollador seguía activo** y Samsung Health seguía en 7.00.6.011, sin reintervención.
- El SDK se identificó en runtime como `1.1.0` (versionCode 1010004), en Android 16 (API 36).

### Pendiente

Lo ya observado antes de la corrida:

- Al primer arranque de la app, `getGrantedPermissions` devolvió **el conjunto vacío** sin lanzar
  excepción: el SDK responde aunque no haya ningún permiso concedido todavía.
- La app cuenta sus arranques y registra qué permisos había concedidos **antes** de pedir nada,
  así que la pregunta "¿el permiso se pierde entre arranques?" se contesta con evidencia:
  basta cerrar la app y volver a abrirla, y comparar `grantedAtStartup` entre corridas.
- Queda por observar si el modo desarrollador de Samsung Health sigue activo sin reintervención y
  si hace falta reabrir Samsung Health entre lecturas.

## 7. Peso y tiempos — dimensionamiento de M3

`PENDIENTE` — requiere la corrida. El volcado mide y registra:

- `timings.exerciseReadMs`, `bodyCompositionReadMs`, `sourceProbesMs`, `totalMs`.
- Peso total del JSON y, aparte, `curveBytes`: el peso de la curva sola.

Ese desglose es necesario porque el archivo contiene la curva **dos veces** —cruda dentro de la
sesión, y en la forma `{t, hr}` que pide el prompt—, de modo que el peso total sobreestima lo que
costaría un volcado incremental. Para M3 el número que sirve es `curveBytes`.

## 8. Qué se construyó

PoC descartable, sin red, sin Firestore, sin plugin Capacitor, sin normalización:

| Archivo | Rol |
|---|---|
| `MainActivity.kt` | pantalla única: botón Leer, botón Compartir, log en pantalla y en Logcat |
| `BridgeReader.kt` | lectura paginada, sondas por fuente, volcado crudo de cada `HealthDataPoint` |
| `Dump.kt` | armado del JSON, estadísticas recalculadas, curva `{t, hr}` |
| `Json.kt` | serialización que **preserva los nulos** |

Dos decisiones que vale la pena registrar:

**Los nulos se preservan.** `JSONObject.put(clave, null)` borra la clave, lo que destruiría
exactamente la información que `fuentes-y-reglas.md` dice que distingue una fuente de otra. Todo
pasa por un helper que escribe `JSONObject.NULL`.

**Los campos no se enumeran a mano donde el SDK puede enumerarlos.** Cada `HealthDataPoint` se
vuelca recorriendo `dataType.allFields`, que es la lista que declara el propio SDK, de modo que
no se omite ningún campo de composición corporal por olvido.

**Se pagina.** `DataResponse.getPageToken()` indica que quedan más datos; sin paginar, un volcado
grande se corta en silencio.

## 9. Cómo correrlo

1. Desbloquear el teléfono y abrir **ShapeUp Bridge**.
2. Tocar **Leer**. La primera vez aparece el diálogo de permisos de Samsung Health: conceder
   ejercicio, frecuencia cardíaca y composición corporal. Ese diálogo es interacción humana
   obligatoria por diseño del SDK.
3. Al terminar, la pantalla muestra el resumen y se habilita **Compartir**.
4. Tocar **Compartir** y sacar `volcado-m1.json` del teléfono. Guardarlo en `salidas/`, que está
   fuera del control de versiones.
