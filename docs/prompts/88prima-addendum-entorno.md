# P88′ — Addendum de entorno (M1)

Este addendum acompaña a `88prima-poc-data-sdk.md`. Todo lo del prompt principal sigue vigente,
incluido su criterio de éxito y su lista de prohibiciones.

**Si algo acá es inviable, pará y reportá.**

---

## Estado del proyecto

El proyecto Android ya está creado con el asistente de Android Studio, como subcarpeta
`ShapeUpBridge/` de este repositorio: `Empty Views Activity`, Kotlin, Kotlin DSL, `minSdk 29`,
package `com.jpcofano.shapeupbridge`. Compila y corre en el teléfono con la actividad vacía.

El archivo `samsung-health-data-api.aar` ya está en `ShapeUpBridge/app/libs/`.

**No regeneres el proyecto, no cambies el wrapper de Gradle, no toques las versiones de AGP ni
de Gradle que puso el asistente.** Están alineadas con la instalación local de Android Studio y
ahí es donde más fácil se rompe el entorno.

## Lo primero que tenés que hacer

**Leer la documentación del SDK antes de escribir código.** Está descomprimida en `sdk/` de
este repositorio e incluye la referencia de API y los ejemplos.

No infieras las firmas de `HealthDataStore`, del sistema de permisos ni de los filtros de
lectura: están documentadas. Este SDK es posterior al corte de tu conocimiento y las firmas
que recuerdes pueden no existir.

Si no encontrás la documentación en `sdk/`, **pará y pedila** en vez de improvisar la API.

## Configuración a agregar

- La dependencia del `.aar` en `ShapeUpBridge/app/build.gradle.kts`.
- `compileOptions` y `kotlinOptions` apuntando a **Java 17**.
- Los permisos que el SDK requiera en el manifiesto, según su documentación.

## Alcance de la lectura

Además de lo que pide el prompt principal para ejercicio, leé **Body Composition** del 05/09 al
15/09 y volcá por cada registro: `startTime`, `uid`, `appId`, `deviceId`, y todos los campos de
composición tal como vienen, **incluidos los `null`**.

No los omitas. Cuál campo viene nulo es lo que distingue una fuente de la otra —el detalle está
en `docs/fuentes-y-reglas.md`— y omitirlos destruye esa información.

## Forma de la salida

Un único JSON, escrito al almacenamiento de la app y expuesto por el **share sheet** de Android;
un botón "compartir" alcanza.

No uses `adb pull` como único camino de salida: la máquina puede no tener `adb` funcionando con
el teléfono.

El archivo que se traiga del teléfono se guarda después en `salidas/`, que está fuera del
control de versiones.

## Qué agregar al reporte

Sobre lo que ya pide el prompt principal:

- Versión de Samsung Health al momento de la corrida, y si el modo desarrollador seguía
  activado sin reintervención.
- Si los permisos del SDK se piden una vez o en cada arranque.
- Cuánto tardó la lectura de las seis sesiones y cuánto pesa el JSON. Eso dimensiona M3: si una
  sola sesión con curva pesa varios megas, el volcado incremental deja de ser una optimización
  y pasa a ser un requisito.
