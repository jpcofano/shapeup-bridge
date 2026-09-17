# PU3 — El puente corre solo

Repo: `C:\dev\shapeup-bridge`. Parte de PU2, que funcionó de punta a punta: login, 11
registros subidos y verificación ✓.

**PU3:** que el puente lea y suba **sin que nadie abra la app**, sin perder registros
grandes y dejando constancia de cada corrida. Con esto, el puente queda cerrado.

Las decisiones están cerradas. Si algo es inviable, **pará y reportá**. No commitees.

**Principio que no se toca: el puente es tonto.** Todo lo que se agrega acá es transporte.
No se agrega ninguna interpretación de los datos.

---

## Paso 0 — Relevar (solo lectura)

1. **Filtros de lectura del SDK:** ¿permite filtrar por hora de actualización o de
   modificación, o solo por hora de inicio? ¿Existe alguna lectura de cambios, del estilo
   "changes since"? Revisá las firmas del `.aar` con `javap`.
2. **Lectura en segundo plano:** ¿el SDK la permite? Buscá en las firmas y en el manifest
   del `.aar` algún permiso o restricción de ese tipo. **No saques conclusiones sin
   probarlo:** la prueba real es la Parte 5.
3. **Fechas de PU1:** dónde está el rango fijo de fechas y cómo se arma el filtro.
4. **WorkManager:** si ya está entre las dependencias.

---

## Parte 1 — Partir los registros grandes

En `Uploader.kt`:

- **Umbral:** 900.000 **bytes UTF-8** del `crudo`.
- **Registro por debajo del umbral:** se sube igual que en PU2, un documento con id
  `{dataType}_{uidSamsung}` y sin campos nuevos.
- **Registro por encima del umbral:**
  - Se corta el string del `crudo` en partes de hasta 900.000 bytes UTF-8, **sin cortar
    un carácter multibyte a la mitad**.
  - Cada parte es un documento con id `{dataType}_{uidSamsung}__p{i}` (`i` desde 1), con
    los cinco campos de PU2 más `parte: i` y `totalPartes: N`, ambos `Int`.
  - Concatenar los `crudo` de las partes en orden reconstruye el JSON exacto.
  - En el mismo batch, se borra el documento entero `{dataType}_{uidSamsung}` si existía,
    para que no convivan las dos versiones.
- Ya no hay "omitido por tamaño" hasta 50 partes, unos 45 MB. **Por encima de 50 partes,
  se omite y se registra como error.**
- El corte de batches por tamaño que ya existe (unos 9 MB) sigue aplicando: un registro
  partido puede repartirse en varios batches.

**Verificación de la Parte 4 de PU2:** tiene que funcionar también si la sesión de
referencia viene partida. Leé todas las partes del servidor, concatená y verificá.

**Prueba forzada:** agregá una constante de depuración que baje el umbral a 200.000 bytes.
Con la sesión de referencia (591 KB) tiene que dar 3 partes, y la verificación tiene que
dar ✓. **Corré esta prueba y después volvé la constante a su valor normal.**

---

## Parte 2 — Lectura incremental

- **Ventana:** cada corrida lee los registros con hora de inicio dentro de los **últimos
  14 días**. La primera corrida después de instalar usa **90 días**.
  - Si el Paso 0 encuentra un filtro por hora de actualización o una lectura de cambios,
    **no lo uses todavía**: reportalo. La ventana fija es más simple y sirve.
- **Sin cambios, no se sube.** Guardá localmente un hash SHA-256 del `crudo` de cada
  `docId` subido, en un archivo en `filesDir` (o en DataStore si ya está). Si el hash no
  cambió, el registro no se sube y cuenta como `sinCambios`.
  - Esto es deduplicación **de transporte**: se evita reenviar bytes idénticos. No decide
    nada sobre el contenido.
  - Si el hash local se pierde, se reenvía todo, que es idempotente. No es un problema.
- **Tipos:** los mismos que PU1 (ejercicio y composición corporal). No agregues otros.
- El archivo de volcado de PU1 se sigue escribiendo **solo** en las corridas manuales.

---

## Parte 3 — Estado de cada corrida

Al terminar cada corrida, manual o automática, escribí el documento
`/ingesta-sdk/{uid}/estado/puente` con **exactamente** estos campos:

| Campo | Tipo | Valor |
|---|---|---|
| `ultimaCorridaMs` | `Long` | Cuándo terminó |
| `versionPuente` | String | Versión de la app |
| `origen` | String | `"manual"` o `"segundo-plano"` |
| `leidos` | Int | Registros leídos |
| `subidos` | Int | Registros subidos |
| `sinCambios` | Int | Registros sin cambios |
| `omitidos` | Int | Registros omitidos |
| `errores` | Int | Errores |
| `duracionMs` | `Long` | Duración de la corrida |
| `mensaje` | String | Solo si hubo error, con el primer error y un máximo de 300 caracteres |

Además, guardá localmente las **últimas 20 corridas** y mostralas en la pantalla principal
en una lista simple: fecha y hora, origen y los contadores.

---

## Parte 4 — Segundo plano con WorkManager

- **Worker:** un `CoroutineWorker` que hace la misma lectura y subida que "Leer y subir",
  con `origen = "segundo-plano"`.
- **Frecuencia:** trabajo periódico **cada 6 horas**, único con
  `ExistingPeriodicWorkPolicy.KEEP`, con las restricciones de red conectada y batería no baja.
- **Se agenda** al iniciar sesión y al abrir la app si hay sesión. **Se cancela** al tocar
  "Salir".
- **Sin usuario de Firebase:** el worker termina sin leer, escribe una notificación
  *"ShapeUp Bridge: entrá a la app para volver a iniciar sesión"* y devuelve `success`,
  para no reintentar en bucle.
- **Notificaciones:** solo si hubo errores u omitidos, con un texto corto y los
  contadores. Si todo salió bien, no se notifica.
  - Pedí el permiso `POST_NOTIFICATIONS` (Android 13 o superior) al iniciar sesión.
- **Reintentos:** un fallo de red devuelve `retry()`, con el backoff por defecto. Cualquier
  otro error devuelve `success()` después de registrarlo en el estado, para no insistir con
  algo que no se arregla solo.
- **Botón "Correr en segundo plano ahora":** encola un `OneTimeWorkRequest` con el mismo
  worker. Sirve para probar.

---

## Parte 5 — Prueba real en el teléfono

Con el teléfono conectado:

1. **Prueba forzada de partes** (Parte 1). Reportá las 3 partes y la verificación ✓.
2. **Corrida manual:** con los datos de PU2 ya subidos, el resultado tiene que ser
   `subidos: 0` y `sinCambios: 11`.
3. **Segundo plano con la app cerrada:** tocá "Correr en segundo plano ahora", cerrá la
   app a la fuerza desde recientes, bloqueá el teléfono, esperá 2 minutos, desbloqueá y
   abrí la app.
   - La lista de corridas tiene que mostrar una de `segundo-plano`.
   - **Si el SDK rechazó la lectura en segundo plano, pará y reportá el error exacto.**
     Es el riesgo principal de PU3, y la alternativa (leer al abrir la app, con un
     recordatorio) se decide después.
4. **Programación:** confirmá con `adb shell dumpsys jobscheduler`, o con la herramienta
   que prefieras, que el trabajo periódico quedó agendado.

El adb inalámbrico se cortó con el teléfono bloqueado en PU2. Para el paso 3 no hace falta
adb mientras el teléfono está bloqueado: lo que cuenta es lo que muestra la app al volver.

---

## Fuera de alcance

- Nuevos tipos de dato.
- Cualquier interpretación.
- El adaptador (PU4).

**Dependencia:** la regla de PU3a tiene que estar desplegada antes de la Parte 5. Si
aparece `PERMISSION_DENIED` al escribir `estado/puente` o una parte, reportalo.

Guardá este prompt como `docs/prompts/pu3-segundo-plano.md`.

---

## Al terminar, reportá

1. El Paso 0.
2. El diff por archivo, resumido, y las dependencias nuevas con su versión.
3. El resultado de compilar.
4. Los cuatro resultados de la Parte 5.
5. Cualquier punto donde hayas parado o te hayas apartado del prompt.
