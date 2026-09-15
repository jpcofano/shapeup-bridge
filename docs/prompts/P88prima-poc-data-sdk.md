# P88′ — PoC de lectura por la vía D (Samsung Health Data SDK)

Este prompt construye una prueba de concepto descartable. **No toca el repo de ShapeUp, no
escribe a Firestore, no crea un plugin de producción.** Es un experimento con un criterio de
éxito numérico y un único entregable: un reporte.

**Si algo acá es inviable, pará y reportá. No reinterpretes ni busques un camino alternativo
por tu cuenta.**

Prerrequisitos ya cumplidos fuera de acá: el modo desarrollador de Samsung Health está activado
con lectura de datos habilitada, el proyecto Android existe y compila vacío en el teléfono, y
el `.aar` está en `ShapeUpBridge/app/libs/`. No hace falta aprobación de partner para leer, así
que no la pidas ni la sugieras como bloqueante.

---

## Objetivo

Determinar si el Samsung Health Data SDK entrega la curva de frecuencia cardíaca de una sesión
de ejercicio custom, y si los valores coinciden con los que el ZIP ya demostró que Samsung
tiene guardados.

## Verdad de campo — no re-derivar

Medido desde el ZIP de Samsung Health del 14/09/2026:

| | |
|---|---|
| Inicio | `2026-09-14T20:34:52.506Z` — epoch ms `1789418092506` |
| Fin | `2026-09-14T21:44:22.441Z` |
| Tipo | `exercise_type = 0` (custom) |
| `custom_id` | `mq1mz4gd_gq`, nombre "ShapeUp" |
| Puntos en la curva | 4133, de los cuales **4112 con FC** |
| FC media / máx / mín | `118,21` / `174` / `83` |
| `heart_rate_sample_count` | `12 839` |

Por la vía Drive (Health Sync → Health Connect) esa misma sesión llega con **2** muestras de FC
y estadísticas derivadas de esas dos: `115` de media y `144` de máximo. El objetivo es ver de
qué lado cae el SDK.

El mismo día hay tres caminatas que **sí** tienen curva por ambas vías, útiles como control:
inicios `2026-09-14T14:16:30.336Z`, `15:48:51.925Z` y `17:13:15.864Z`, con 490, 628 y 659
muestras respectivamente.

## Alcance

El proyecto Android `ShapeUpBridge/`, dentro del repositorio del puente, ya creado con el
asistente de Android Studio. Actividad única, un botón, salida por Logcat y a un archivo JSON.

Leé antes `docs/contexto.md`, `docs/verdad-de-campo.md` y `docs/fuentes-y-reglas.md` de este
repositorio.

**No construyas un plugin Capacitor.** Si el PoC funciona, el plugin es otro prompt.

## Qué tiene que hacer

1. Importar el SDK y obtener el `HealthDataStore` con `HealthDataService.getStore()`.
2. Pedir permiso de lectura para los tipos **Exercise** y **Heart rate**.
3. Leer las sesiones de ejercicio del 14/09/2026 con un filtro de tiempo que cubra el día
   completo en hora local.
4. Para **cada** sesión encontrada, volcar: inicio con milisegundos, fin, tipo, nombre si lo
   hay, `custom_id` si lo hay, calorías, duración, y **el tamaño de la lista `log` más cuántos
   de sus elementos traen FC**.
5. Para la sesión que empieza en `1789418092506`, volcar la lista `log` completa a un JSON con
   la forma `[{ "t": <epoch ms>, "hr": <bpm> }, ...]` y calcular media, máximo y mínimo.
6. Si la lista `log` viene nula o vacía, **no la rellenes con nada y no busques un sustituto.**
   Reportá exactamente eso.

Antes de declarar que no hay datos, probá también con `ReadSourceFilter` por tipo de
dispositivo — reloj y dispositivo local — para descartar el filtro de fuente como causa.

## Criterio de éxito

- **Éxito**: la sesión custom trae una lista `log` con al menos 4000 entradas con FC, y las
  estadísticas recalculadas caen dentro de ±1 bpm de `118,21` / `174` / `83`.
- **Fracaso limpio**: la sesión custom trae `log` nulo o con menos de 10 entradas, **y** al
  menos una de las tres caminatas del mismo día trae `log` con cientos de entradas. Eso
  demuestra que el techo es por tipo de actividad, no por la API.
- **Ambiguo**: cualquier otra combinación, en particular que ninguna sesión traiga `log`.
  Reportalo como ambiguo y **no lo interpretes**.

## Entregable

Un solo documento, `docs/REPORTE-M1.md`, con:

- Versión del SDK y versión de la app de Samsung Health en el teléfono.
- Tabla de todas las sesiones del 14/09 encontradas, con el tamaño de `log` de cada una.
- Las estadísticas recalculadas de la sesión de ShapeUp contra la verdad de campo de arriba.
- Si el nombre "ShapeUp" y el `custom_id` viajan por el SDK.
- Si aparecen sesiones que la vía Drive no tiene, y si el SDK ofrece alguna forma de
  distinguir las autodetectadas sin curva mejor que `live_data_internal` vacío.
- **Fricción operativa**: si el modo desarrollador se desactiva solo, si el permiso se pierde,
  si hace falta reabrir Samsung Health entre lecturas.

Ese último punto no es decorativo. La vía D sirve solo si es estable sin intervención. Si el
modo desarrollador se cae con cada actualización de Samsung Health, eso cambia la decisión
aunque la lectura funcione perfecto.

## Qué **no** hay que hacer

- No escribir a Firestore, ni usar red, ni agregar Firebase. Eso es M2.
- No implementar lectura incremental ni trabajo en background. Eso es M3.
- No normalizar nada: ni tipos, ni claves, ni fuentes. El volcado es crudo, con ceros y nulos
  incluidos. El motivo está en `docs/contexto.md`.
- No construir el plugin Capacitor.
- No modificar el ADR #032 ni ningún documento del roadmap. El reporte alimenta una decisión
  que se toma en la conversación de diseño, no acá.
- No pedir la aprobación de partner a Samsung.
