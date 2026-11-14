# Movilidad · Reabastecimiento por evento masivo

El backend consume `com.citypass.espacios.ActividadCreada.v2` mediante el receptor Kafka que ya
existe. Para una actividad programada de al menos 1.000 personas, con un horario de fin todavía
vigente, crea una tarea preventiva por cada estación activa a 300 m o menos. La hora de
finalización de la actividad se muestra como límite: el reabastecimiento se coordina antes de ese
momento. La tarea no cambia el estado ni la cantidad de bicicletas de una estación.

El inbox existente deduplica por `metadata.eventId`; la base también impide dos tareas para la
misma pareja actividad–estación. Se conserva `correlationId` tal como llega y, si Avro lo entrega
nulo, se usa la convención de Espacios `act-<actividadId>`.

La API devuelve las tareas vigentes en `GET /api/v1/admin/festival-restock-tasks`. En modo seguro
requiere rol ADMIN. El frontend, el cierre de tareas, los cambios/cancelaciones de actividades y la
publicación a Analítica quedan para otro paso.

## Probar en Windows sin conexión al bus

Con Java 21 y una base MySQL local disponible (ver README), desde PowerShell, en el repo backend:

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=festival-local"
```

Este perfil escucha en `127.0.0.1`, deja Kafka apagado e importa el catálogo Ecobici. Usá una
base local; Flyway aplica la migración al iniciar. Si el puerto o la base tienen otros datos,
configuralos con las variables que indica el README.

En otra consola PowerShell:

```powershell
$api = 'http://127.0.0.1:8080/api/v1'
$now = [DateTimeOffset]::UtcNow
$id = [Guid]::NewGuid().ToString()
$body = @{
    actividadId = $id
    correlationId = "act-$id"
    titulo = 'Festival de Primavera'
    estado = 'PROGRAMADA'
    capacidad = 5000
    fechaHoraInicio = $now.AddMinutes(1).ToUnixTimeMilliseconds()
    fechaHoraFinalizacion = $now.AddMinutes(30).ToUnixTimeMilliseconds()
    ubicacion = @{ lat = -34.5994; lng = -58.3988 }
    direccion = 'Av. Córdoba 2100'
    espacioNombre = 'Plaza Houssay'
    actorSub = $null
} | ConvertTo-Json -Compress

Invoke-RestMethod -Method Post -Uri "$api/dev/festival/actividad-creada" -ContentType 'application/json' -Body $body
Invoke-RestMethod -Uri "$api/admin/festival-restock-tasks" |
    Where-Object activityId -eq $id | Format-Table
```

Con el catálogo incluido aparecen Hospital de Clínicas, Plaza Houssay y Facultad de Medicina.
Repetir el POST con el mismo `$body` deja las mismas tareas. La ruta de simulación solo existe
con el perfil `festival-local`.

Para la prueba automatizada se necesita Docker iniciado:

```powershell
.\gradlew.bat test --tests '*FestivalRestockIntegrationTest'
```

Base del parche: `a37e35e` de Movilidad Backend. Si se integra más adelante sobre un `main`
diferente, revisar las versiones de Flyway antes de aplicarlo a una base compartida.

## Estados y prueba de stock

Con la migración V23, el listado incluye el historial. Para cada estación activa dentro de 300 m,
el objetivo inicial es el 90 % de la capacidad, redondeado hacia arriba. Si al crear la tarea el
stock ya alcanza el objetivo, queda realizada con motivo «Stock suficiente». El backend revisa
las tareas abiertas cada cinco segundos: si detecta el objetivo alcanzado antes del límite,
registra «Reabastecida»; al vencer, la conserva como «Vencida».

En Administración de Movilidad podés ajustar el objetivo de una pendiente o confirmarla
manualmente. «Sugerir traslado» busca una estación activa a hasta 2 km con excedente, excluye
estaciones con tareas de un evento vigente y conserva al menos el 50 % de la capacidad en el origen.
Es una recomendación por reglas; no reserva ni mueve bicicletas.

Para probarlo en Recoleta, cambiá en el ejemplo local las coordenadas a
`-34.5862976, -58.3925127` y la capacidad del evento a 1100. Usá un fin suficientemente lejano
para entrar al panel. Desde Reabastecimiento, elegí una estación pendiente con un anclaje libre y
ajustá su objetivo a una bicicleta más que el stock disponible. En **Administración → Bicicletas**
trasladá una bicicleta **Disponible** de otra estación a la elegida. Volvé a Reabastecimiento y
verificá en **Realizadas** que diga «Reabastecida». Las demás estaciones del evento conservan
su estado y una tarea completada sigue en el historial aunque baje el stock después.
