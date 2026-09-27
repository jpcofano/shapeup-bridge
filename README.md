# ShapeUp Bridge

El puente de lectura de Samsung Health para ShapeUp: una app Android chica en Kotlin que lee
datos con el Samsung Health Data SDK y los vuelca crudos. No es ShapeUp, no comparte código con
ShapeUp y no se compila junto con ShapeUp.

## Cómo se commitea

Un commit por hito. El trabajo propio queda separado del ruido que genera el asistente de
Android Studio en su primera corrida, que es mucho y no se revisa línea por línea.

## Orden de trabajo

1. Crear el proyecto Android con el asistente de Android Studio, como subcarpeta
   `ShapeUpBridge/`. No se generan archivos de Gradle a mano: las versiones de Gradle y del
   plugin de Android tienen que coincidir con la instalación local.
2. Copiar el `.aar` del SDK a `ShapeUpBridge/app/libs/`.
3. Ejecutar el prompt P88′ con su addendum (van en `docs/prompts/`).

## Los cuatro hitos

- **M1** — leer por SDK y volcar a un archivo JSON. Es lo que se está haciendo.
- **M2** — que el JSON viaje solo a Firebase.
- **M3** — lectura incremental y corrida periódica en background.
- **M4** — el adaptador del lado TypeScript, que vive en el repo de ShapeUp, no acá.

## Corrida pedida por ShapeUp (push)

Además de la corrida periódica de cada 6 horas, ShapeUp puede pedir una corrida en el momento:
una Cloud Function (P89, en el repo de ShapeUp) manda un push de datos silencioso al token que el
puente guarda en `/ingesta-sdk/{uid}/estado/dispositivo`, y el puente corre con
`origen: 'pedido'`. La periódica sigue igual: es la red de seguridad para cuando el push no
llega.

Android pone dos trabas que el puente no puede saltear:

- **App detenida a la fuerza** (Ajustes → Apps → ShapeUp Bridge → Forzar detención, o borrar
  datos): el sistema **no le entrega pushes** hasta que alguien vuelva a abrir la app a mano. No
  hay forma de evitarlo desde el código. **Tampoco corre la periódica**: forzar la detención
  cancela también los trabajos agendados. Al abrir la app, WorkManager lo detecta y reagenda
  todo solo; no hay que volver a iniciar sesión ni tocar nada más. Si en ShapeUp el pedido no
  llega nunca, lo primero es abrir el puente una vez. Un push rechazado mientras la app estaba
  detenida puede llegar igual un rato después de abrirla (en la prueba de P90, 81 s después) y
  disparar una corrida pedida tardía; es inofensiva, pero para entonces ShapeUp ya puede haber
  dado el pedido por vencido. (Si la detención cae justo mientras corre
  una corrida, Android puede relanzar el puente al instante para terminarla y la app deja de
  estar detenida sola. Para probar este caso, forzar la detención con el puente quieto.)
- **Ahorro de batería de Samsung.** One UI es más agresivo que Android base: una app
  "optimizada" puede recibir el push tarde o no recibirlo, y la corrida se demora. En la
  pantalla del puente la línea **Batería** dice si la exclusión está concedida, y el botón
  **Pedir sin optimización de batería** la pide. Conviene además que el puente **no** esté en
  Ajustes → Batería → Límites de uso en segundo plano → *Aplicaciones en suspensión* o
  *Aplicaciones en suspensión profunda*, y si se puede, que esté en *Aplicaciones que nunca se
  suspenden*. Esa lista la maneja Samsung aparte y el puente no puede consultarla.

El permiso de notificaciones de Android 13+ **no hace falta** para el push: los mensajes de
datos llegan sin él. El puente lo pide al iniciar sesión solo para avisar errores del worker.

En la pantalla del puente se ve cada eslabón: **Token registrado** (si el servidor confirmó el
token y cuándo) y **Último push recibido** (cuándo llegó y cómo terminó la corrida que disparó).
Si ShapeUp dice que mandó el push y acá no figura, el corte está entre FCM y el teléfono.

El puente reescribe el token cuando cambia, comparándolo con el último que el servidor le
confirmó (guardado en el teléfono), no leyendo Firestore en cada corrida. Por eso, **si alguien
borra `estado/dispositivo` a mano, el puente no se entera** y sigue diciendo "Token registrado:
sí". Se arregla cerrando sesión y volviendo a entrar en el puente.

**Comportamiento conocido — entrega tardía después de una detención forzada.** Un push que
Android rechazó porque la app estaba detenida no se pierde: se entrega hasta un par de minutos
después de que alguien abre el puente (medido: 81 s) y dispara una corrida pedida tardía que
termina bien. No es un error. Lo único a saber es que para entonces ShapeUp ya puede haber
mostrado el pedido como vencido.

### Pruebas de P90 (27/09/2026, Galaxy S25+ SM-S936U1, Android 16)

Todas se dispararon con el **botón real de ShapeUp**; ninguna con escritura de administrador.
Detalle, precisión de cada medición y cómo correr lo pendiente en
[docs/REPORTE-P90.md](docs/REPORTE-P90.md).

| # | Prueba | Resultado |
|---|---|---|
| 1 | Punta a punta | ✅ 6,34 s del botón al fin de la corrida (función en frío) |
| 2a | Doze forzado por adb | ✅ 3,05 s desde que arranca la función |
| 2b | Doze real | **PENDIENTE** — va de noche, procedimiento en el reporte |
| 3 | Dos pedidos seguidos | ⚠️ una sola corrida, pero porque la función descartó el segundo (`muy-seguido`); la política `KEEP` del puente **PENDIENTE** |
| 4 | Sin conexión | ✅ el push llegó al volver la red; una sola corrida |
| 5 | Token cambiado a la fuerza | ✅ token nuevo escrito al entrar; el push llegó a él |
| 6 | App detenida a la fuerza | ✅ ni push ni periódica; al abrirla se reagenda todo solo (y entrega tardía, ver arriba) |
| — | Botón de batería | verificado por logs; **PENDIENTE** tocarlo en el teléfono |

## Qué se versiona y qué no

- **`sdk/` sí se versiona**, el `.aar` incluido. Son un par de megas una sola vez, y a cambio el
  proyecto compila en una máquina nueva sin volver a bajar el SDK del sitio de Samsung.
- **`salidas/` nunca se versiona.** Contiene volcados de datos personales de salud.

## Documentos

- [docs/contexto.md](docs/contexto.md) — qué hace el puente, qué no hace y por qué. Se lee
  primero.
- [docs/verdad-de-campo.md](docs/verdad-de-campo.md) — la sesión de referencia contra la que se
  verifica M1.
- [docs/fuentes-y-reglas.md](docs/fuentes-y-reglas.md) — las reglas que ya están decididas y que
  el puente deliberadamente no implementa.
- [docs/REPORTE-P90.md](docs/REPORTE-P90.md) — pruebas del push de P90, con su precisión, lo
  pendiente y cómo correr la prueba de Doze real.
