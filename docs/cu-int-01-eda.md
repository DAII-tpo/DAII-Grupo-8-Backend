# CU-INT-01 en el bus EDA del equipo

La rama incorpora los modelos `cultural_destinations` y `city_incidents` (migración V6),
handlers para siete tópicos del CU, destinos culturales y filtrado de estaciones afectadas.

**Se usa el mecanismo EDA integrado en main**: `EventBusListener`, `EventDispatcher`,
`event_inbox` y `EventHandler`. No se usa el webhook ni otro outbox paralelo.

Activa consumo con `EVENTBUS_CONSUMER_ENABLED=true` y las credenciales del bus,
según `docs/event-bus.md`. Cada handler persiste datos dentro de la misma transacción
de idempotencia del inbox. El radio configurable de incidentes es
`movilidad.incidents.radius-meters` (default 300 m).

**Implementado en esta rama:** publicar `RecomendacionDestinoActualizada` por outbox EDA,
recalcular después de cambios de eventos e incidentes y cada 15 minutos para eventos
próximos o ya iniciados; invalidar eventos cancelados/espacios deshabilitados. Espacios
aceptó el contrato de salida, incluido `estacionId` como long.

**Pendientes de terceros:** Espacios publicará `EventoComunitarioPublicado`, `EventoCancelado`
y `EspacioEstadoCambiado` y facilitará el contrato exacto de `ReservaConfirmada`;
este reemplaza a `ReservaCreada` y no se suscribe hasta conocer sus campos.
Pruebas punta a punta contra EDA una vez que estén los productores activos.
No se publican indicadores BI desde esta rama.
Las métricas de viajes viven en la rama independiente `feat/integracion-bi`.
