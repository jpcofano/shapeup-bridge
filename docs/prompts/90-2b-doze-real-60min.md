# Prompt P90 · prueba 2b — Doze real en 60 minutos

**Para:** Claude Code, en el repo del puente (`shapeup-bridge`, proyecto en `ShapeUpBridge/`).
**Regla general:** si algo no coincide con lo que dice este prompt, **pará y reportá**. No lo reinterpretes ni improvises una alternativa. No commitees: commitea Juan.

---

## Contexto

Falta la prueba 2b del reporte P90: comprobar que un pedido de ShapeUp despierta al puente cuando el teléfono está en **Doze real**, no simulado. Juan la hizo en una hora, sin cable y sin depuración inalámbrica. Tu trabajo empieza **cuando Juan vuelve a enchufar el teléfono**: reconstruir qué pasó y reportarlo.

## Decisiones cerradas

1. **Duró unos 60 minutos**, no una noche entera.
2. **Doze natural.** No se usó `force-idle`.
3. **El puente siguió excluido de la optimización de batería** ("sin restricciones"). Es el mejor caso. La prueba sin exclusión y el botón de batería **siguen pendientes**.
4. **La evidencia es que el puente corra**, es decir, que aparezca un `estado/puente` nuevo con `origen: 'pedido'` después del pedido. **No se restan horas de relojes distintos**: teléfono, PC, FCM y la función son relojes distintos. Las horas se anotan como referencia, nunca como medida.
5. **El pedido se hizo desde ShapeUp en la PC, con el teléfono todavía apagado.** Cualquier cosa que haya pasado después de que Juan encendió el teléfono no cuenta como resultado de Doze.

## Lo que hizo Juan

1. Desenchufó el teléfono y lo dejó quieto con la pantalla apagada, conectado al wifi de siempre.
2. A los 50-55 minutos, sin tocar el teléfono, pidió una corrida desde ShapeUp en la PC y esperó 5 minutos.
3. Recién después encendió el teléfono, miró "Último push recibido" en el puente y lo enchufó.

## Qué hacer

1. **¿Llegó a Doze?** Leé `adb shell dumpsys batterystats --history` y buscá las transiciones de `device_idle` (`light` / `full`) en la última hora y media.
   - Reportá si el teléfono estaba en Doze profundo, en liviano o en ninguno cuando llegó el pedido.
   - Si el historial no alcanza para decirlo, **pará y reportá**. No lo deduzcas.
2. **¿Corrió el puente?** En Firestore, solo lectura, listá los `estado/puente` de las últimas dos horas con su id, su `origen` y lo que leyeron y subieron.
   - Separá los que aparecieron **antes** de que Juan encendiera el teléfono de los que aparecieron **después**. Para ordenarlos usá el orden de los documentos y lo que cuente Juan, no restas de horas.
   - Ojo: el `estado/puente` con `origen: 'pedido'` que escribió el test de `KEEP` no responde a ningún push. No lo cuentes.
3. **¿Llegó el push?** Contrastá con lo que Juan vio en "Último push recibido": si avanzó y cuántas veces.

## Qué reportar

Escribí el reporte completo en `docs/auditorias/ultimochat.md` y actualizá la sección 2b de `docs/REPORTE-P90.md` con:

- El estado de Doze al llegar el pedido, según `batterystats`.
- Si el pedido generó corrida con el teléfono apagado. Si **no** la generó, eso es un resultado, no un error de la prueba.
- Qué queda sin probar: Doze de varias horas, Doze sin exclusión y el botón de batería.
- Si la sección 2b calcula demoras restando horas entre relojes distintos, corregila según la decisión 4.

No commitees.
