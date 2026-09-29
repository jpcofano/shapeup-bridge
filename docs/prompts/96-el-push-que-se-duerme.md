# 96 — El push que se duerme

Repo: `jpcofano/shapeup-bridge`, proyecto en `ShapeUpBridge/`.

**Regla general:** si algo no coincide con lo que dice este prompt, **pará y reportá**. No lo reinterpretes. No commitees: commitea Juan.

---

## El problema

La prueba de Doze del 28/09 mostró esto, siempre con el reloj del teléfono en las dos puntas:

- **22:50:52.673** — FCM entrega el push al puente y le da una lista blanca temporal hasta las **22:52:24**.
- **22:50:52.9** — la CPU se vuelve a dormir, 0,2 s después.
- **22:52:04 a 22:52:07** — corre `BridgeWorker`. Arrancó porque un temporizador **ajeno** despertó el teléfono.

Fueron **72 segundos** entre el push y la corrida, y la corrida entró con 20 segundos de margen antes de que venciera la lista blanca. Con el teléfono despierto, esa misma cadena tarda 6,34 s. **Hoy el pedido funciona en Doze de casualidad.**

## Decisiones cerradas

1. **El objetivo:** que después de un push de prioridad alta, la corrida arranque **dentro de la ventana que da FCM**, sin depender de que otra cosa despierte el teléfono.
2. **El arreglo no puede depender de la exclusión de batería.** Hoy el teléfono de Juan está "sin restricciones", pero el puente tiene que funcionar igual en uno que no lo esté. La familia todavía no lo usa, pero tiene que quedar previsto: si algo del arreglo solo sirve con exclusión, **pará y reportá**.
3. **No se toca** la política `KEEP` ni el mutex de `BridgeRun`. El test de `KEEP` tiene que seguir pasando.
4. **Las medidas, con un solo reloj.** Las demoras salen del historial de batería del teléfono, con el mismo reloj en las dos puntas. No se restan horas de la función, de la PC ni de FCM.
5. **Hasta que Juan lo diga, no se toca el teléfono.** Está en una prueba de Doze que termina alrededor de las 11:05. Las Partes 1 y 2 son de repo y no lo necesitan. Para la Parte 3 esperá a que Juan confirme que lo enchufó.

---

## Parte 1 — Diagnóstico (solo lectura)

Hay tres hipótesis. Reportá cuál explica los 72 segundos, con evidencia del código:

- **A · El encolado no se espera.** `onMessageReceived` llama a WorkManager y vuelve sin esperar a que termine de agendar, así que la CPU se duerme antes. Mostrá cómo se encola y si se espera el resultado de la `Operation`.
- **B · El trabajo no es *expedited*.** Sin `setExpedited`, JobScheduler puede diferirlo hasta la próxima ventana de mantenimiento. Anotá:
  - si se usa `setExpedited` y con qué `OutOfQuotaPolicy`;
  - el `minSdk` y el `targetSdk`;
  - si `BridgeWorker` implementa `getForegroundInfo`, que en API < 31 lo necesita para ser *expedited*.
- **C · El push llega degradado.** Revisá si la función de ShapeUp manda `android.priority: high` y si el puente registra `originalPriority` y `priority` del mensaje. Si no las registra, anotá que no se puede saber. Si el problema está del lado de la función, eso es otro repo: **pará y reportá**, no lo toques.

Si ninguna de las tres cierra, **pará y reportá** antes de escribir código.

## Parte 2 — Arreglo (repo, sin instalar)

El cambio mínimo que corrija lo que encontraste en la Parte 1. Lo esperable:

- El trabajo del pedido se encola como ***expedited***, con `RUN_AS_NON_EXPEDITED_WORK_REQUEST` como respaldo si se agota la cuota, y sigue usando nombre único con `KEEP`.
- `onMessageReceived` **espera** el resultado del encolado, con un tope bien por debajo del tiempo que Android le da a ese método. Si vence el tope, lo registra.
- El puente **registra** `originalPriority` y `priority` de cada push en su historial local, junto a la corrida que generó. Es barato y la próxima vez nos ahorra adivinar.

Si el arreglo real resulta distinto de esto, explicá por qué antes de hacerlo.

## Parte 3 — Verificación (solo cuando Juan diga que enchufó el teléfono)

1. Instalá **sin desinstalar**, para no perder la sesión.
2. Corré `RequestedRunKeepTest` con `am instrument`, **no** con `connectedAndroidTest`. Tiene que seguir dando un solo trabajo y una sola corrida.
3. **Prueba rápida con Doze forzado.** Es solo un control de humo; la prueba real de Doze profundo va de noche, otro día.
   - Llevá el teléfono a Doze profundo con `dumpsys deviceidle force-idle deep` y **desenchufado lógico** (`dumpsys battery unplug`).
   - Juan hace un pedido desde ShapeUp.
   - Medí en el historial de batería cuánto pasa entre la entrega del push y el arranque de `BridgeWorker`.
   - Después restaurá todo: `dumpsys battery reset` y `dumpsys deviceidle unforce`.
4. Hacé lo mismo **sin la exclusión de batería**. Sacala con el comando de `docs/REPORTE-P90.md` y **volvé a ponerla al terminar**, dejándolo verificado con `dumpsys deviceidle whitelist`.

---

## Qué reportar

Escribí el reporte completo en `docs/auditorias/ultimochat.md`, con:

1. Cuál de las hipótesis era, con la evidencia.
2. Qué cambiaste y por qué.
3. La demora entre push y worker, antes (72 s) y después, con y sin exclusión, medida con un solo reloj.
4. Que el test de `KEEP` sigue pasando.
5. Qué queda sin probar: Doze profundo natural de varias horas con el arreglo puesto.

Agregá en `docs/REPORTE-P90.md` una línea que remita a este prompt para el hallazgo de los 72 s.

No commitees. Guardá este prompt como `docs/prompts/96-el-push-que-se-duerme.md`.
