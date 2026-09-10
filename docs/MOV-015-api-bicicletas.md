# MOV-015 API REST de bicicletas

## Endpoints

- `POST /api/v1/bikes`: alta de bicicleta (`ADMIN`).
- `GET /api/v1/bikes/{id}`: consulta por ID (`ADMIN`).
- `GET /api/v1/bikes/station/{stationId}`: consulta completa por estación (`ADMIN`).
- `GET /api/v1/bikes/available?stationId={id}`: consulta de disponibilidad (`USER` o `ADMIN`).
- `PATCH /api/v1/bikes/{id}/status`: cambio administrativo de estado (`ADMIN`).
- `PATCH /api/v1/bikes/{id}/station`: traslado entre estaciones (`ADMIN`).
- `GET /api/v1/bikes/{id}/status-history`: historial de estados (`ADMIN`).
- `DELETE /api/v1/bikes/{id}`: baja lógica (`ADMIN`).

Todos los endpoints se publican en Swagger/OpenAPI. MOV-015 no modifica la configuración de seguridad ni crea
usuarios locales. La autenticación y la asignación efectiva de los roles `ADMIN` y `USER` pertenecen al módulo de
Login Federado del Grupo 2. Hasta contar con ese contrato, la lógica de bicicletas se verifica mediante tests
unitarios y la configuración OAuth2 Resource Server existente permanece sin cambios.

## Reglas operativas

- Las altas comienzan en el estado indicado (por defecto `AVAILABLE`) y registran una entrada inicial en
  `bike_status_history`. No pueden comenzar en `IN_USE`.
- Una bicicleta `AVAILABLE` debe tener estación para ofrecerse como disponible.
- Las estaciones de alta o traslado deben existir, estar activas, no estar dadas de baja y tener capacidad.
- La consulta administrativa por estación también admite estaciones inactivas o en mantenimiento, pero no
  estaciones dadas de baja.
- Las bicicletas con baja lógica quedan en `OUT_OF_SERVICE`, se excluyen de todas las consultas activas y
  conservan su historial.
- `IN_USE` se reserva al flujo transaccional de inicio/finalización de viajes. La API administrativa no permite
  entrar ni salir de ese estado, trasladar la bicicleta ni darla de baja.
- Un cambio hacia el mismo estado se rechaza: no genera una entrada histórica redundante.

## Matriz de transiciones administrativas

| Estado actual | Estados permitidos |
|---|---|
| `AVAILABLE` | `MAINTENANCE`, `OUT_OF_SERVICE`, `STOLEN` |
| `MAINTENANCE` | `AVAILABLE`, `OUT_OF_SERVICE`, `STOLEN` |
| `OUT_OF_SERVICE` | `MAINTENANCE`, `STOLEN` |
| `STOLEN` | `MAINTENANCE`, `OUT_OF_SERVICE` |
| `IN_USE` | Ninguno mediante la API administrativa |

El retorno directo `OUT_OF_SERVICE -> AVAILABLE` no se acepta: una unidad retirada de servicio debe pasar por
`MAINTENANCE` antes de volver a estar disponible. Una bicicleta recuperada desde `STOLEN` tampoco vuelve
directamente a `AVAILABLE`; primero debe evaluarse en mantenimiento.

Cada cambio admitido registra estado anterior, estado nuevo, motivo y fecha. `changed_by_user_id` queda en `null`
mientras no exista integración con el módulo responsable de autenticación y usuarios, condición permitida por el
modelo de datos.
