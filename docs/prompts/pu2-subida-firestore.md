# PU2 — El puente sube lo que lee a Firestore

Repo: `C:\dev\shapeup-bridge` (proyecto Android en `ShapeUpBridge/`). PU1 está hecho y
verificado: la app lee Samsung Health por el Data SDK y vuelca un JSON a un archivo.

**PU2:** que lo leído viaje solo a Firestore, con la misma cuenta de Google que usa la web
de ShapeUp.

Las decisiones están cerradas. Si algo es inviable o el código no es como se describe,
**pará y reportá**. No commitees: el commit lo hace Juan.

---

## Principio que no se toca

**El puente es tonto.** Lee y vuelca crudo:

- no normaliza tipos;
- no compone claves de negocio;
- no decide qué fuente gana;
- no descarta campos, no deduplica y no convierte ceros en ausencias.

Todo eso vive en TypeScript, en el repo de ShapeUp. Lo único que se agrega en PU2 es el
**transporte**.

---

## Paso 0 — Relevar (solo lectura)

Antes de editar, reportá:

1. La estructura del proyecto: el `applicationId`, los archivos Kotlin y qué hace cada uno.
2. Cómo PU1 lee: qué tipos de dato del SDK, cómo arma el JSON y dónde lo escribe.
3. Si cada registro que devuelve el SDK trae un **id propio** (`uid` o equivalente) y cómo
   se accede. Mirá las firmas del `.aar` con `javap` si hace falta, como en PU1.
4. Versiones reales de AGP, Gradle, Kotlin y `compileSdk`.
5. Si ya hay algo de Firebase en el proyecto.

**Si los registros no traen un id propio, pará acá y reportá**: la clave del documento
depende de eso.

---

## Parte 1 — Firebase en el proyecto

**Juan hace estos pasos a mano en la consola de Firebase, antes o durante este prompt.
Vos no los podés hacer. Listalos en el reporte si faltan.**

1. En el proyecto `shapeup-41e74`, agregar una app Android con el `applicationId` del
   Paso 0.
2. Registrar el **SHA-1** del keystore de debug. Se obtiene con la tarea `signingReport`
   desde el panel de Gradle de Android Studio. `gradlew` desde consola falla porque
   `JAVA_HOME` no está seteada.
3. Descargar `google-services.json` y dejarlo en `ShapeUpBridge/app/`.

**Tu parte:**

- Agregá el plugin `com.google.gms.google-services` y el **Firebase BoM** en su última
  versión compatible con este AGP, con `firebase-auth` y `firebase-firestore`.
- Para el login con Google, usá **Credential Manager**
  (`androidx.credentials` + `googleid`).
- Reportá las versiones que elegiste.
- **No declares el plugin de Kotlin:** desde AGP 9 viene incorporado. Es una lección de PU1.
- **Agregá `google-services.json` al `.gitignore`.** No es un secreto, pero identifica el
  proyecto y el repo va a GitHub.

---

## Parte 2 — Login

- Pantalla principal: si no hay usuario de Firebase, un botón **"Entrar con Google"**.
  Con usuario, mostrar el email y un botón **"Salir"**.
- El login usa Credential Manager con `GetGoogleIdOption`, el `default_web_client_id` que
  genera `google-services.json` y `signInWithCredential` en Firebase Auth.
- **Ningún id de usuario escrito en el código.** Todo sale de
  `FirebaseAuth.getInstance().currentUser`.
- La sesión persiste entre aperturas de la app. Firebase ya lo hace: verificalo.

---

## Parte 3 — Subida

**Destino:** `/ingesta-sdk/{uid}/registros/{docId}`, con `uid` = `currentUser.uid`.

**Un documento por registro del SDK:**

| Campo | Valor |
|---|---|
| `docId` | `{dataType}_{uidSamsung}`. Si el id del SDK trae caracteres que Firestore no acepta en un id (por ejemplo `/`), reemplazalos por `_` y reportalo |
| `dataType` | El nombre del tipo según el SDK, tal cual |
| `uidSamsung` | El id del registro según el SDK, tal cual |
| `leidoMs` | `System.currentTimeMillis()` al leer |
| `versionPuente` | `BuildConfig.VERSION_NAME` |
| `crudo` | El registro completo serializado a JSON, **el mismo JSON que PU1 escribe al archivo para ese registro**. No lo cambies |

**Ningún otro campo.** Las reglas rechazan cualquier extra.

**Cuándo se sube:**

- Botón **"Leer y subir"**: hace la misma lectura de PU1 y sube cada registro con
  `set(docId, datos)`.
- Volver a subir el mismo registro pisa el documento: es idempotente.
- **El volcado a archivo de PU1 se mantiene**, como respaldo y para verificar.

**Tamaño:** si un `crudo` supera los 1.000.000 caracteres, no se sube. Se cuenta como
"omitido por tamaño" y su `docId` se loguea.

**Resultado en pantalla:**

- subidos, omitidos y errores, más el tiempo total;
- si Firestore no confirma en 15 s porque no hay red, **"En cola: se sube cuando haya
  señal"**. El SDK de Firestore en Android encola las escrituras solo.

**Batching:** hasta **20 registros por batch**. Con registros de 118 KB, 20 dan menos de
10 MB por commit, que es el máximo de Firestore.

---

## Parte 4 — Verificación contra la sesión de referencia

Después de subir, desde la app:

1. Leé de vuelta el documento de la sesión de ShapeUp (la de 4133 entradas en `log`).
2. Parseá su `crudo` y recalculá las mismas cinco cifras de PU1:

   | Cifra | Valor esperado |
   |---|---|
   | Entradas en `log` | 4133 |
   | Con `heartRate` | 4112 |
   | Media | 118,213 |
   | Máximo | 174 |
   | Mínimo | 83 |

3. Mostralas en pantalla con ✓ o ✗ contra lo esperado.

Esto prueba que el viaje por Firestore no alteró nada. Es la misma lógica de verificación
de PU1: si ya existe, reusala.

---

## Dependencia externa

La regla de la colección la agrega **otro prompt, en el repo de ShapeUp** (PU2a), y Juan
la despliega. **Si la subida da `PERMISSION_DENIED`, lo más probable es que la regla no
esté desplegada todavía.** Reportalo así, sin intentar arreglarlo desde acá.

---

## Fuera de alcance

- Lectura incremental y corrida en background (PU3).
- Cualquier interpretación de los datos (PU4, en TypeScript).
- Borrar documentos subidos.

Guardá este prompt en el repo como `docs/prompts/pu2-subida-firestore.md`. Si `docs/` no
existe, crealo.

---

## Al terminar, reportá

1. El Paso 0 completo.
2. Los pasos de consola que faltan, si faltan.
3. El diff por archivo, resumido, y las versiones elegidas.
4. El resultado de compilar.
5. Si pudiste correrlo: subidos, omitidos, errores, tiempo y la verificación de la
   Parte 4.
6. Cualquier punto donde hayas parado o te hayas apartado del prompt.
