# BI · Caso 4: cantidad de estaciones afectadas por incidentes

Implementación en la rama `feat/bi-estaciones-afectadas`, derivada de `feat/cu-int-01-ruta-urbana` para
reutilizar los consumidores de Reclamos/Emergencias, la tabla `city_incidents` y `GeoDistance`.
**No fusionar directamente con `feat/integracion-bi` sin coordinar la historia de Git**:
esta rama depende de CU-INT-01 y la rama original BI contiene los eventos de viajes.

## Contrato propuesto a BI

Tipo `com.citypass.movilidad.EstacionesAfectadasActualizadas`:

- `cantidadEstacionesAfectadas` (int): estaciones distintas ACTIVAS/no eliminadas situadas a 300 m
  o menos de al menos un incidente de Reclamos/Emergencias que afecta la circulación y sigue activo.
- `cantidadIncidentesActivos` (int): incidentes activos conocidos por Movilidad, aun cuando no afecten estaciones.
- `calculadaEn` (string): timestamp ISO-8601 UTC del snapshot.

Cada estación se cuenta como máximo una vez aunque haya más de un incidente cercano.
Se publica después del commit de un incidente creado/resuelto y periódicamente cada 15 minutos
(reconciliación ante cambios del catálogo y recuperación de fallos). Se usa el outbox EDA
sin realizar HTTP dentro de la transacción de consumo Kafka.

**La métrica es un snapshot actual, no la cantidad acumulada de estaciones que alguna vez
fueron afectadas.** BI deberá registrar los snapshots si necesita una serie temporal.

## Pendientes de integración

- BI confirma nombres y tipos del schema y si el dashboard se embeberá por iframe.
- Ejecutar `./gradlew test` y verificar el registro del event type en el gateway.
- Probar un incidente activo, dos incidentes superpuestos y la resolución del último incidente.
- Los eventos de Espacios aún no están registrados; no afectan el cálculo de la métrica.
