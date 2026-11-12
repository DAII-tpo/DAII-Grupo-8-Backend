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
