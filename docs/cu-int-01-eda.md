# CU-INT-01 en el bus EDA del equipo

La rama incorpora los modelos `cultural_destinations` y `city_incidents` (migración V6),
handlers para ocho tópicos del CU, destinos culturales y filtrado de estaciones afectadas.

**Se usa el mecanismo EDA integrado en main**: `EventBusListener`, `EventDispatcher`,
`event_inbox` y `EventHandler`. No se usa el webhook ni otro outbox paralelo.

Activa consumo con `EVENTBUS_CONSUMER_ENABLED=true` y las credenciales del bus,
según `docs/event-bus.md`. Cada handler persiste datos dentro de la misma transacción
de idempotencia del inbox. El radio configurable de incidentes es
`movilidad.incidents.radius-meters` (default 300 m).

**Falta para cerrar el CU**: publicar `RecomendacionDestinoActualizada` por EDA,
recalcular tras incidentes, eventos y por scheduler, gestionar cancelaciones de reservas,
y pruebas punta a punta contra EDA. No se publican indicadores BI desde esta rama.
Las métricas de viajes viven en la rama independiente `feat/integracion-bi`.
