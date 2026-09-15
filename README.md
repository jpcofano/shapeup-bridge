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
