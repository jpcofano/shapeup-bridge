# Fuentes y reglas

Resumen de las decisiones que ya están tomadas en el repo de ShapeUp. Están acá **para que se
entienda por qué el puente no las implementa**, no para implementarlas.

## Clave canónica de un registro

Inicio en epoch UTC con milisegundos + tipo normalizado + `appId`. Se verificó que las tres vías
entregan el mismo epoch al milisegundo, así que la comparación es exacta y sin tolerancia.

## Tipos

El vocabulario difiere por vía. El workout custom llega como `OTHER` por el SDK, `TRAINING` por
Health Connect y `0` en la exportación manual; se normaliza a `otro`, que significa "sin
clasificar por Samsung".

**No significa que la sesión haya sido de fuerza**: fuerza y realidad virtual usan el mismo
workout custom y ninguna vía de Samsung puede distinguirlas.

## Ningún cero se escribe

Un `0.0` que significa "no hay dato" se convierte en campo ausente. En la sesión de fuerza
llegan `count = 0`, `distance = 0.0` y `maxSpeed = 0.0`, que son exactamente ese caso.

**Por eso el puente vuelca crudo, ceros y nulos incluidos**: quién decide que un cero es
ausencia es el adaptador, no el puente.

## Composición corporal: tres aplicaciones escriben, dos métodos miden

| `appId` | Fuente | Rol |
|---|---|---|
| `com.sec.android.app.shealth` con `deviceId 9XdbeBZKBf` | reloj, bioimpedancia de muñeca | origen |
| `nl.appyhapps.healthsync` | balanza, vía Garmin | puente, preferencia 1 |
| `com.garmin.android.connectmobile` | balanza | puente, preferencia 2, solo peso |

Reloj y balanza miden con métodos distintos y dan valores distintos para el mismo campo —
`skeletal_muscle_mass` difiere en casi cuatro kilos entre los dos. **No se mezclan nunca**:
series separadas. Los dos puentes de la balanza transportan la misma medición y solo entra uno.

Además, **el reloj escribe `weight` sin medirlo**: lo hereda del perfil. Es un valor plausible
con timestamp fresco, y el adaptador lo descarta.

Nada de esto lo hace el puente. El puente vuelca los tres registros tal como vienen, con sus
`appId`, sus `deviceId` y sus campos nulos, **y los nulos importan**: cuál campo viene nulo es
lo que distingue una fuente de la otra.
