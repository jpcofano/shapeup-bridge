# 90 — El puente escucha (lado Android)

Repo: **ShapeUp Bridge** (`C:\dev\shapeup-bridge`). **Su par es P89, en el repo de ShapeUp.**
Este prompt no sirve solo: sin P89 no hay quien mande el push; sin este, el push no lo recibe
nadie.

## Qué problema resuelve

El puente sube los datos del reloj cada 6 horas. Si alguien termina de entrenar y quiere ver su
sesión con la frecuencia cardíaca, tiene que esperar. El 25/09 eso se vio como si la app
hubiera fallado.

A partir de acá, **ShapeUp pide y el puente responde en segundos**: una Cloud Function le manda
un push silencioso y el puente hace una corrida en el momento.

**La corrida periódica de 6 horas no se toca.** Es la red de seguridad para cuando el push no
llega, que en Android pasa más de lo que uno quisiera.

**Las decisiones están cerradas.** Si alguna es inviable, **pará y reportá**. No commitees.

---

## Parte 1 — El token, en Firestore

- Sumá `firebase-messaging` a las dependencias.
- `FirebaseMessagingService` propio:
  - **`onNewToken`** → escribe `/ingesta-sdk/{uid}/estado/dispositivo` con `fcmToken`,
    `actualizadoMs`, `modelo` y `versionPuente`. **`update`/merge, nunca `set` entero**: la
    función escribe `ultimoPushMs` en ese mismo documento y no hay que pisárselo.
  - Además, **al iniciar sesión y en cada corrida**, si el token guardado no coincide con el
    actual, se reescribe. Un token puede cambiar sin que `onNewToken` llegue, y un token viejo
    es un puente mudo que nadie nota.
- Sin sesión iniciada no se escribe nada: el documento vive bajo el uid.

---

## Parte 2 — El push dispara una corrida

- **`onMessageReceived`** → encolar el **mismo worker de siempre** como
  `OneTimeWorkRequest`, **expedited**, con la política de trabajo único que corresponda para que
  dos pushes seguidos no disparen dos corridas encimadas.
- **No se abre la app ni se muestra notificación.** Es un mensaje de datos, silencioso.
- La corrida escribe `/ingesta-sdk/{uid}/estado/puente` como siempre, pero con
  **`origen: 'pedido'`** (P89 lo agrega a los orígenes válidos de las reglas). Así se distingue
  en la tarjeta una corrida pedida de una de rutina.
- **La periódica de 6 horas sigue igual**, con la misma política que ya usa.

---

## Parte 3 — Lo que Android va a hacer en contra

Esto no es opcional: sin esto el push funciona en las pruebas y falla en la vida real.

- **App forzada a detenerse**: el sistema no le entrega pushes hasta que alguien la abra. No
  hay forma de evitarlo. **Documentalo en el README** del puente.
- **Ahorro de batería de Samsung**, que es más agresivo que el de Android base. El puente
  tiene que poder **pedir la exclusión de optimización de batería** desde su pantalla, y
  **mostrar si está concedida o no**. Que se vea, no que se adivine.
- **Android 13 o más**: el permiso de notificaciones **no hace falta** para mensajes de datos.
  Que no se pida de gusto.
- **Doze**: la prioridad alta del mensaje y el trabajo expedited son justamente para esto.
  Verificá que la corrida entre igual con la pantalla apagada.

---

## Parte 4 — Que se vea en la app del puente

En la pantalla de estado, dos líneas más:

- **Token registrado**: sí o no, y cuándo se actualizó.
- **Último push recibido**: cuándo, y si la corrida que disparó terminó bien.

Con eso, cuando algo no llegue se sabe **en qué eslabón se cortó** sin tener que mirar logs.

---

## Qué probar, de verdad y no en el papel

1. **De punta a punta**: en ShapeUp apretar el botón y ver que el puente corre en segundos.
2. **Con la pantalla apagada** y el teléfono quieto un rato, para que entre en Doze.
3. **Dos pedidos seguidos**: una sola corrida, no dos encimadas.
4. **Sin conexión en el teléfono**: el push llega cuando vuelve, o no llega y entra la
   periódica. Ninguna de las dos cosas puede dejar el estado inconsistente.
5. **Token cambiado a la fuerza** (borrar datos de la app y volver a entrar): se reescribe y el
   siguiente push llega.
6. **App detenida a la fuerza**: confirmá que **ni el push ni la periódica** llegan, y que al
   abrir la app todo se reagenda solo. Es el caso que hay que documentar, no arreglar.
   *(Corregida durante la ejecución: la versión original esperaba que la periódica entrara
   igual, pero forzar la detención en Android cancela también los trabajos agendados.)*

---

## Al terminar, reportá

1. El diff, resumido.
2. Qué probaste de la lista de arriba y qué pasó en cada caso. **Lo que no pudiste probar,
   decilo** — es peor una prueba supuesta que una prueba faltante.
3. Cuánto tarda, medido, desde que se aprieta el botón en ShapeUp hasta que el puente termina
   la corrida.
4. Qué quedó documentado en el README sobre batería y app detenida.
5. Dónde paraste o te apartaste.

Guardá este prompt como `docs/prompts/90-el-puente-escucha.md` en el repo del puente.
