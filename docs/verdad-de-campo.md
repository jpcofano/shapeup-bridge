# Verdad de campo

La sesión de referencia contra la que se verifica M1. Es un dato medido: **ningún número se
cambia**.

Sesión de entrenamiento custom llamada "ShapeUp", del 14/09/2026, verificada por tres vías
independientes: exportación manual de Samsung Health, DataViewer del Data SDK y la exportación
automática vía Health Connect.

| Campo | Valor |
|---|---|
| `uid` | `1ed92d6b-3280-4e02-a05c-7123cda97205` |
| Inicio | `2026-09-14T20:34:52.506Z` — epoch ms `1789418092506` |
| Fin | `2026-09-14T21:44:22.441Z` |
| `exerciseType` | `OTHER` |
| `customTitle` | `ShapeUp` |
| Duración | 4152 s (activa) |
| Calorías | 604,0 |
| Entradas en `log` | **4133** |
| Entradas con `heartRate` | 4112 |
| FC media / máx / mín | **118,21 / 174 / 83** |

## Criterio de éxito de M1

El puente devuelve 4133 entradas para esa sesión y las tres estadísticas caen dentro de ±1 bpm.

## Controles

- El 14/09 tiene **seis** sesiones de ejercicio. Dos son autodetectadas y no tienen curva.
- La caminata de `2026-09-14T17:13:15.864Z` tiene `log` de **677** entradas y FC máxima 119.
  Sirve como control de una actividad reconocida.
- Entre las dos últimas entradas del `log` de la sesión de ShapeUp hay un salto de **17,48 s**:
  es una pausa real y coincide con la diferencia entre duración activa y transcurrida. Si el
  volcado no la muestra, algo está interpolando.
