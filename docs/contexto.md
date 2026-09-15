# Contexto del puente

Este es el documento que se lee primero al arrancar sin contexto.

## Qué hace

Lee sesiones de ejercicio y composición corporal de Samsung Health con el Samsung Health Data
SDK y las vuelca crudas.

## Qué NO hace, y por qué

> El puente no normaliza nada. No traduce tipos de actividad, no compone claves, no decide qué
> fuente de medición gana, no descarta campos, no deduplica.
>
> Todas esas reglas existen y están decididas, pero viven en TypeScript en `lib/` del repo de
> ShapeUp. Implementarlas también en Kotlin significaría tener la misma lógica escrita dos
> veces en dos lenguajes, y el día que se corrija una, se corregiría solo una.
>
> El puente ocupa el mismo lugar que ocupaba la exportación manual del ZIP: es un volcado
> crudo. Lo único que cambia es que lo dispara una app en vez de una persona.

Las reglas están resumidas en [fuentes-y-reglas.md](fuentes-y-reglas.md), y están ahí para que
se entienda por qué el puente no las implementa.

## Por qué existe

Samsung publica a Health Connect el resumen de una sesión de entrenamiento pero no la serie de
frecuencia cardíaca. Para una sesión de 69 minutos con 12.839 muestras medidas, Health Connect
entrega 2.

Cualquier herramienta que lea Health Connect hereda ese techo. El Data SDK lee directamente de
la app de Samsung Health y entrega la serie completa.

## Requisitos de entorno

| Requisito | Mínimo |
|---|---|
| Android | 10 / API 29 o superior |
| Java | 17 o superior |
| Samsung Health | 6.30.2 o superior |
| Modo desarrollador de lectura | activado en Samsung Health |
| Dispositivo | **teléfono físico — el SDK no funciona en emulador** |
