# B0 — Bootstrap del repositorio del puente

Este es el primer prompt de un proyecto nuevo, en `C:\dev\shapeup-bridge`. El repositorio git
ya está inicializado en la rama `main` y **vacío**: no hay ningún commit todavía.

Armá todo: el `.gitignore`, la estructura de carpetas, los documentos, y dejá el trabajo en
**un solo commit**.

**Orden obligatorio:** primero el `.gitignore`, después todo lo demás, y el commit al final. Si
algo queda rastreado antes de que exista el ignore, sigue rastreado después aunque lo agregues
al ignore más tarde.

**Git: qué sí y qué no.** Podés usar `git add`, `git commit` y `git status`. **No** uses
`push`, `remote`, `branch`, `checkout`, `reset`, `rebase`, `clean` ni `commit --amend`. No hay
remoto configurado y no hay que configurarlo.

**No sobrescribas archivos existentes en silencio.** No hay historial al que volver todavía. Si
un archivo que tenés que crear ya existe, **pará y reportá** en vez de reemplazarlo.

Tu tarea acá es **estructura, documentación y el commit inicial**. No escribís código, no creás
el proyecto Android, no tocás Gradle.

---

## Contexto mínimo

ShapeUp es una app de fitness familiar en React, TypeScript y Firebase, que vive en otro
repositorio y en otra máquina. Consume datos de Samsung Health.

Este proyecto es **el puente**: una app Android chica y separada, en Kotlin, cuyo único trabajo
es leer datos de Samsung Health con el Samsung Health Data SDK y volcarlos crudos. No es
ShapeUp, no comparte código con ShapeUp, y no se compila junto con ShapeUp.

Está deliberadamente separado porque su ciclo de vida es distinto: Kotlin y Gradle contra
TypeScript y Vite. Mantenerlo aparte evita que termine dentro del build de la PWA.

---

## Estructura a crear

```
shapeup-bridge/
├── .gitignore
├── README.md
├── docs/
│   ├── contexto.md
│   ├── verdad-de-campo.md
│   ├── fuentes-y-reglas.md
│   └── prompts/            (vacía — se llena a mano)
├── sdk/                    (vacía — acá va el SDK descomprimido)
└── salidas/                (vacía — acá van los JSON traídos del teléfono)
```

Las carpetas vacías llevan un `.gitkeep` vacío para que git las registre y quede claro que son
intencionales. La excepción es `salidas/`, que va ignorada entera y por lo tanto no lleva
`.gitkeep`.

El proyecto Android se va a crear **después y con el asistente de Android Studio**, como
subcarpeta `ShapeUpBridge/`. **No lo crees vos ni generes archivos de Gradle.** Las versiones
de Gradle y del plugin de Android tienen que coincidir con la instalación local de Android
Studio, que no ves desde acá, y es el punto donde más fácil se rompe el entorno.

---

## Contenido de los archivos

### `.gitignore`

Va **primero**, antes que cualquier otro archivo.

```gitignore
# Android / Gradle
*.iml
.gradle/
build/
local.properties
.idea/
captures/
.externalNativeBuild/
.cxx/

# Firma — nunca al repo
*.jks
*.keystore
keystore.properties

# Firebase (llega en M2)
google-services.json

# Datos personales de salud
salidas/
```

Dos decisiones ya tomadas que **no** hay que cambiar:

- **El `.aar` y la carpeta `sdk/` SÍ se versionan.** Son un par de megas, se versionan una vez,
  y a cambio el proyecto compila en una máquina nueva sin tener que volver a bajar el SDK del
  sitio de Samsung. No los agregues al ignore.
- **`salidas/` nunca se versiona.** Contiene volcados de datos personales de salud.

### `README.md`

Un documento corto con el orden de operaciones y el estado. Que diga:

- Qué es este proyecto, en dos líneas: el puente de lectura de Samsung Health para ShapeUp.
- Que se commitea por etapa, un commit por hito, para que el trabajo quede separado del ruido
  que genera el asistente de Android Studio en su primera corrida.
- El orden de trabajo: primero se crea el proyecto Android con el asistente, después se copia
  el `.aar` a `ShapeUpBridge/app/libs/`, después se ejecuta el prompt P88′ con su addendum.
- Los cuatro hitos del plan, una línea cada uno:
  - **M1** — leer por SDK y volcar a un archivo JSON. Es lo que se está haciendo.
  - **M2** — que el JSON viaje solo a Firebase.
  - **M3** — lectura incremental y corrida periódica en background.
  - **M4** — el adaptador del lado TypeScript, que vive en el repo de ShapeUp, no acá.
- Que `sdk/` **sí** se versiona, para que el proyecto sea autosuficiente en una máquina nueva,
  y que `salidas/` **nunca** se versiona porque contiene datos personales de salud.

### `docs/contexto.md`

El documento que un Claude Code que arranca sin contexto tiene que leer primero. Que contenga:

**Qué hace el puente:** lee sesiones de ejercicio y composición corporal de Samsung Health con
el Data SDK y las vuelca crudas.

**Qué NO hace, y por qué.** Esta es la parte importante del documento:

> El puente no normaliza nada. No traduce tipos de actividad, no compone claves, no decide qué
> fuente de medición gana, no descarta campos, no deduplica.
>
> Todas esas reglas existen y están decididas, pero viven en TypeScript en `lib/` del repo de
> ShapeUp. Implementarlas también en Kotlin significaría tener la misma lógica escrita dos
> veces en dos lenguajes, y el día que se corrija una, se corregiría solo una.
>
> El puente ocupa el mismo lugar que ocupaba la exportación manual del ZIP: es un volcado
> crudo. Lo único que cambia es que lo dispara una app en vez de una persona.

**Por qué existe:** Samsung publica a Health Connect el resumen de una sesión de entrenamiento
pero no la serie de frecuencia cardíaca. Para una sesión de 69 minutos con 12.839 muestras
medidas, Health Connect entrega 2. Cualquier herramienta que lea Health Connect hereda ese
techo. El Data SDK lee directamente de la app de Samsung Health y entrega la serie completa.

**Requisitos de entorno**, en una tabla: Android 10 / API 29 o superior, Java 17 o superior,
Samsung Health 6.30.2 o superior, modo desarrollador de lectura activado en Samsung Health, y
**el SDK no funciona en emulador** — hace falta un teléfono físico.

### `docs/verdad-de-campo.md`

La sesión de referencia contra la que se verifica M1. Es un dato medido y **ningún número se
cambia**.

Sesión de entrenamiento custom llamada "ShapeUp", del 14/09/2026, verificada por tres vías
independientes: exportación manual de Samsung Health, DataViewer del Data SDK, y la exportación
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

**Criterio de éxito de M1:** el puente devuelve 4133 entradas para esa sesión y las tres
estadísticas caen dentro de ±1 bpm.

Anotar también estos controles:

- El 14/09 tiene **seis** sesiones de ejercicio. Dos son autodetectadas y no tienen curva.
- La caminata de `2026-09-14T17:13:15.864Z` tiene `log` de **677** entradas y FC máxima 119.
  Sirve como control de una actividad reconocida.
- Entre las dos últimas entradas del `log` de la sesión de ShapeUp hay un salto de **17,48 s**:
  es una pausa real y coincide con la diferencia entre duración activa y transcurrida. Si el
  volcado no la muestra, algo está interpolando.

### `docs/fuentes-y-reglas.md`

Resumen de las decisiones que ya están tomadas en el repo de ShapeUp. Están acá **para que se
entienda por qué el puente no las implementa**, no para implementarlas.

**Clave canónica de un registro:** inicio en epoch UTC con milisegundos + tipo normalizado +
`appId`. Se verificó que las tres vías entregan el mismo epoch al milisegundo, así que la
comparación es exacta y sin tolerancia.

**Tipos:** el vocabulario difiere por vía. El workout custom llega como `OTHER` por el SDK,
`TRAINING` por Health Connect y `0` en la exportación manual; se normaliza a `otro`, que
significa "sin clasificar por Samsung". **No significa que la sesión haya sido de fuerza**:
fuerza y realidad virtual usan el mismo workout custom y ninguna vía de Samsung puede
distinguirlas.

**Ningún cero se escribe.** Un `0.0` que significa "no hay dato" se convierte en campo ausente.
En la sesión de fuerza llegan `count = 0`, `distance = 0.0` y `maxSpeed = 0.0`, que son
exactamente ese caso. **Por eso el puente vuelca crudo, ceros y nulos incluidos**: quién decide
que un cero es ausencia es el adaptador, no el puente.

**Composición corporal: tres aplicaciones escriben, dos métodos miden.**

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

### `docs/prompts/.gitkeep`

Carpeta vacía. Acá se copian a mano `88prima-poc-data-sdk.md` y su addendum, que vienen de la
otra máquina.

---

## Criterios de aceptación

- [ ] El `.gitignore` creado **primero**, con el contenido de arriba y sin `*.aar` ni `sdk/`.
- [ ] La estructura existe, con los `.gitkeep` donde corresponda y ninguno en `salidas/`.
- [ ] Los cuatro documentos escritos, con todos los números de `verdad-de-campo.md` intactos.
- [ ] `docs/contexto.md` dice explícitamente que el puente no normaliza y por qué.
- [ ] **No** existe ningún archivo de Gradle, ni carpeta `ShapeUpBridge/`, ni código Kotlin.
- [ ] **Un solo commit** en `main`, con mensaje `B0: estructura y documentos de contexto`.
- [ ] `git status` limpio después del commit.
- [ ] En el reporte: la salida de `git log --oneline` y de `git ls-files`, para poder verificar
      qué quedó rastreado.
- [ ] Reportá cualquier archivo que ya existiera y no hayas tocado.
