# Eventos de Movilidad para BI

Esta rama es independiente del CU-INT-01: publica los hechos de viaje para el indicador
**viajes exitosos por mes**, sin añadir gráficos ni indicadores.

- `ViajeIniciado` al guardar un viaje activo.
- `ViajeTerminado` al confirmar su devolución.
- Ambos comparten `id` y `correlationId = viaje-<id>`.
- `EventPublisher` escribe en el outbox **dentro de la transacción de TripService**.
- `OutboxRelay` envía al gateway si `EVENTBUS_PUBLISHER_ENABLED=true`.
- Los contratos Avro se registran desde `resources/eventbus/event-types`.

La definición y las fechas ISO 8601 respetan el contrato de BI del 22/10/2026.
Los campos opcionales se declaran como uniones Avro de null y string.

**Antes de activar en un entorno compartido**: confirmar si los event types ya existen
y comparar su esquema exacto. El registrador no reemplaza contratos existentes.

BI calcula el indicador de incidencias por mes con `ReclamoCreado` y
`EmergenciaCreada`, publicados por sus módulos correspondientes.

La integración visual del HTML de BI se prepara en la rama frontend
`feat/integracion-bi`, con `VITE_ANALYTICS_EMBED_URL`.
