# MOV-021 — Manejo de errores y validaciones REST

Estandariza cómo responde el backend cuando algo sale mal: un único formato de error para toda
la API, las validaciones declaradas una sola vez, y ningún error esperable terminando en un 500.

## Formato común de error

Toda respuesta 4xx o 5xx tiene la misma forma, la genere un controller, Bean Validation o el
propio Spring MVC (`ErrorResponse`):

```json
{
  "timestamp": "2026-09-17T14:30:00Z",
  "status": 404,
  "error": "Not Found",
  "code": "STATION_NOT_FOUND",
  "message": "La estación con ID 99 no existe",
  "path": "/api/v1/stations/99"
}
```

| Campo | Descripción |
|---|---|
| `timestamp` | Instante en que se generó el error, en UTC |
| `status` | Código de estado HTTP |
| `error` | Frase estándar del estado (`Not Found`, `Conflict`, ...) |
| `code` | Código estable, para que el cliente ramifique sin parsear el mensaje |
| `message` | Mensaje legible, en español |
| `path` | Path de la solicitud que falló |
| `errors` | Detalle por campo. **Solo** aparece en errores de validación |

En un error de validación se agrega `errors`, con un elemento por campo rechazado:

```json
{
  "timestamp": "2026-09-17T14:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDATION_ERROR",
  "message": "capacity: must be greater than 0",
  "path": "/api/v1/stations",
  "errors": [
    { "field": "name", "message": "must not be blank" },
    { "field": "capacity", "message": "must be greater than 0" }
  ]
}
```

Las respuestas **nunca** incluyen stack traces, nombres de constraints de la base ni mensajes de
parseo de Jackson: ese detalle se registra en el log del servidor (`ERROR` para 5xx, `WARN` para
conflictos de integridad y concurrencia, `DEBUG` para el resto) y al cliente le llega solo un
mensaje accionable.

## Excepciones de dominio

Todas heredan de `ApiException`, que lleva el estado HTTP y el `code` con los que se responde.
Un service lanza la excepción que corresponde y no necesita saber nada de HTTP.

| Excepción | Estado | `code` | Cuándo |
|---|---|---|---|
| `ResourceNotFoundException` | 404 | `RESOURCE_NOT_FOUND` | El recurso no existe o fue dado de baja |
| `StationNotFoundException` | 404 | `STATION_NOT_FOUND` | Caso concreto del anterior, del que hereda |
| `BusinessRuleException` | 409 | `BUSINESS_RULE_VIOLATION` | Regla de negocio o estado del recurso incompatible |
| `ValidationException` | 400 | `VALIDATION_ERROR` | Dato de entrada inválido que no se puede expresar con anotaciones |

Para agregar una excepción nueva alcanza con extender la que corresponda: no hay que tocar el
handler.

## Estados que devuelve la API

`GlobalExceptionHandler` extiende `ResponseEntityExceptionHandler`, así que hereda el mapeo que
Spring MVC ya hace de sus propias excepciones y solo reescribe el cuerpo. Eso cubre casos que
antes caían en el handler genérico y se iban en 500.

| Estado | Se devuelve cuando |
|---|---|
| 400 | Cuerpo ausente o ilegible; campo del DTO inválido; parámetro, path o header faltante, con tipo incorrecto o fuera de rango |
| 404 | Recurso de dominio inexistente, o ruta no mapeada |
| 405 | Método HTTP no permitido en el recurso |
| 409 | Regla de negocio incumplida, violación de integridad en la base, o conflicto de concurrencia |
| 415 | `Content-Type` de la solicitud no soportado |
| 406 | No se puede generar una respuesta en el formato pedido |
| 500 | Solo errores realmente inesperados |

Las excepciones de Spring Security (`AccessDeniedException`, `AuthenticationException`) se
vuelven a lanzar para que el filtro de seguridad decida entre 401 y 403; sin eso el handler
genérico las convertiría en 500.

## Validaciones

Las reglas de entrada viven en los DTOs y en los parámetros de los controllers, con anotaciones
de Bean Validation. Ningún controller valida a mano ni repite un rango: las restricciones que se
usan en más de un lugar están definidas una sola vez en `com.citypass.movilidad.validation`.

| Restricción | Regla |
|---|---|
| `@Latitude` | Entre -90.0 y 90.0 |
| `@Longitude` | Entre -180.0 y 180.0 |
| `@EntityId` | Identificador positivo. Rechaza con 400 antes de ir a la base, en lugar de devolver un 404 engañoso |

`@EntityId` no implica que el ID exista: eso lo resuelve el service lanzando
`ResourceNotFoundException`.

## Alcance del cambio en estaciones

`POST /api/v1/stations` y `PATCH /api/v1/stations/{id}` ahora validan el cuerpo. `name`,
`latitude`, `longitude` y `capacity` pasan a ser obligatorios en ambos: el `PATCH` ya reemplazaba
todos los campos editables con lo recibido, así que omitir uno escribía `null` en una columna
`NOT NULL` y terminaba en 500. `status` y `source` siguen siendo opcionales y se completan con
`ACTIVE` y `MANUAL` cuando no vienen.
