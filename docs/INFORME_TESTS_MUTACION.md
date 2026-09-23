# Informe de Implementación y Resultados de Tests de Mutación

- **Rama**: `test/MOV-048-tests-mutacion`
- **Proyecto**: `DAII-Grupo-8-Backend` (Microservicio de Movilidad Urbana)
- **Fecha**: 23 de septiembre de 2026
- **Herramienta utilizada**: [PIT (Pitest)](https://pitest.org) v1.22.1 (Gradle Plugin `info.solidsoft.pitest:1.19.0`)
- **Entorno de ejecución**: Java 21 (Toolchain), Gradle 9.5.1, JUnit 5 (Jupiter)

---

## 1. Introducción y Fundamentos

### 1.1. ¿Qué son los Tests de Mutación?
La cobertura de código tradicional (medida mediante herramientas como JaCoCo) calcula qué líneas o ramas condicionales fueron ejecutadas durante las pruebas. Sin embargo, una prueba puede ejecutar el 100% de una línea sin validar correctamente sus efectos o aserciones.

El **Testing de Mutación** somete el código a fallas simuladas introduciendo pequeños cambios sintácticos (*mutaciones*) en el bytecode compilado, tales como:
- Modificar operadores lógicos (`==` por `!=`, `<` por `<=`).
- Alterar operaciones aritméticas (`+` por `-`).
- Forzar retornos nulos o listas vacías en métodos.
- Eliminar llamadas a métodos `void` (como guardados o setters de estado).

Por cada mutación generada se ejecuta la suite de tests:
- **Mutante Asesinado (*Killed*)**: Al menos un test falló al ejecutarse contra el código mutado, demostrando que el test es sensible al comportamiento del código y detecta el defecto.
- **Mutante Sobreviviente (*Survived*)**: Todos los tests pasaron a pesar de la alteración, evidenciando una falta de aserción, caso borde no contemplado o un mutante equivalente.
- **Mutante Equivalente (*Equivalent Mutant*)**: Una mutación que altera la sintaxis pero mantiene semánticamente idéntico el comportamiento del programa (no puede ser detectado por ningún test).

---

## 2. Configuración Implementada en el Proyecto

### 2.1. Configuración de `build.gradle`
Se incorporó el plugin oficial `info.solidsoft.pitest` y se parametrizó el bloque `pitest`:

```groovy
plugins {
    // ...
    id 'info.solidsoft.pitest' version '1.19.0'
}

pitest {
    junit5PluginVersion = '1.2.1'
    targetClasses = [
        'com.citypass.movilidad.service.*',
        'com.citypass.movilidad.mapper.*',
        'com.citypass.movilidad.validation.*',
        'com.citypass.movilidad.controller.*'
    ]
    targetTests = [
        'com.citypass.movilidad.service.*',
        'com.citypass.movilidad.mapper.*',
        'com.citypass.movilidad.validation.*',
        'com.citypass.movilidad.controller.*',
        'com.citypass.movilidad.exception.*'
    ]
    excludedTestClasses = [
        '*IntegrationTest',
        '*QueryTest',
        '*PersistenceTests',
        '*ApplicationTests',
        '*SecureModeTest'
    ]
    threads = 4
    outputFormats = ['HTML', 'XML']
    timestampedReports = false
    mutationThreshold = 0
}
```

### 2.2. Decisiones de Diseño y Alcance
1. **Foco en Lógica de Negocio y Dominio**: Se priorizaron las capas `service`, `mapper`, `validation` y `controller`, donde residen las reglas de negocio, validaciones y transformaciones críticas de movilidad (bicicletas, viajes, estaciones, incidencias y mantenimiento).
2. **Exclusión de Tests Pesados de Integración con Docker**: Se excluyeron deliberadamente los tests anotados con `@Testcontainers` (`*IntegrationTest`, `*QueryTest`, `*PersistenceTests`, `*ApplicationTests`). Esto permite ejecutar los tests de mutación de forma rápida y determinística en cualquier máquina de desarrollo (con o sin Docker local) y optimizar los tiempos de ejecución a menos de 2 minutos.

### 2.3. Cómo Ejecutar los Tests de Mutación
Para ejecutar el análisis localmente:
```bash
./gradlew pitest
```
El reporte HTML resultante se genera de forma consolidada en:
`build/reports/pitest/index.html`

---

## 3. Resumen Ejecutivo de Resultados

Tras la configuración inicial y el fortalecimiento de casos de prueba para matar mutantes en límites de cálculo y aserciones de estado, se obtuvieron las siguientes métricas globales:

| Métrica | Valor Obtenido | Descripción |
|---|:---:|---|
| **Clases Analizadas** | **20** | Componentes de lógica de negocio, mapeos y controladores |
| **Line Coverage** | **99%** (614 / 623) | Porcentaje de líneas de código evaluadas por la suite |
| **Mutaciones Generadas** | **334** | Variaciones sintácticas inyectadas por PIT |
| **Mutantes Asesinados (*Killed*)** | **321** | Mutantes detectados y eliminados por los tests |
| **Mutation Coverage** | **96%** | Proporción de mutantes eliminados sobre el total |
| **Test Strength** | **99%** (321 / 324) | Efectividad de las aserciones sobre el código cubierto |
| **Tiempo Total de Análisis** | **1m 36s** | Ejecución concurrente en 4 hilos |

---

## 4. Desglose por Operador de Mutación

| Mutador PIT | Generados | Asesinados | % Éxito | Observaciones |
|---|:---:|:---:|:---:|---|
| **Conditionals Boundary** | 12 | 12 | **100%** | Detección perfecta de cambios `<` vs `<=`, `>` vs `>=` |
| **Primitive Returns** | 2 | 2 | **100%** | Retornos booleanos y numéricos |
| **Increments** | 4 | 4 | **100%** | Operaciones `++` y `--` |
| **Void Method Call** | 87 | 84 | **97%** | Invocaciones a métodos sin retorno (setters, guardados) |
| **Boolean True/False Returns** | 4 | 4 | **100%** | Retornos de banderas lógicas |
| **Null Return Values** | 86 | 80 | **93%** | Retorno forzado a `null` (6 sin cobertura en clases base) |
| **Math Mutator** | 8 | 8 | **100%** | Inversión de fórmulas matemáticas |
| **Empty Object Return Vals** | 30 | 29 | **97%** | Retornos forzados a `Collections.emptyList()` |
| **Negate Conditionals** | 101 | 98 | **97%** | Negación de condiciones `if` |

---

## 5. Resultados Detallados por Paquete y Clase

### 5.1. Capa de Servicios (`com.citypass.movilidad.service`)

| Clase | Line Coverage | Mutation Coverage | Test Strength | Mutantes (Killed / Gen) |
|---|:---:|:---:|:---:|:---:|
| `BikeService.java` | 100% | **98%** | **100%** | 88 / 90 |
| `BikeStatusTransitionPolicy.java` | 86% | **100%** | **100%** | 4 / 4 |
| `EcobiciStationImportService.java` | 100% | **100%** | **100%** | 12 / 12 |
| `GeoBoundingBox.java` | 100% | **100%** | **100%** | 19 / 19 |
| `IncidentService.java` | 100% | **87%** | **97%** | 33 / 38 |
| `MaintenanceService.java` | 100% | **96%** | **100%** | 26 / 27 |
| `NearbyStationService.java` | 100% | **100%** | **100%** | 6 / 6 |
| `StationAvailability.java` | 100% | **100%** | **100%** | 3 / 3 |
| `StationAvailabilityService.java` | 100% | **100%** | **100%** | 11 / 11 |
| `StationService.java` | 100% | **100%** | **100%** | 19 / 19 |
| `TripService.java` | 100% | **97%** | **97%** | 32 / 33 |
| **Subtotal Paquete** | **99%** | **97%** | **99%** | **253 / 262** |

### 5.2. Capa de Mapeo (`com.citypass.movilidad.mapper`)

| Clase | Line Coverage | Mutation Coverage | Test Strength | Mutantes (Killed / Gen) |
|---|:---:|:---:|:---:|:---:|
| `EcobiciStationMapper.java` | 98% | **98%** | **98%** | 42 / 43 |
| **Subtotal Paquete** | **98%** | **98%** | **98%** | **42 / 43** |

### 5.3. Capa de Controladores (`com.citypass.movilidad.controller`)

| Paquete / Controladores | Line Coverage | Mutation Coverage | Test Strength | Mutantes (Killed / Gen) |
|---|:---:|:---:|:---:|:---:|
| Controladores REST (7 clases) | 90% | **90%** | **100%** | **26 / 29** |

---

## 6. Análisis de Mutantes y Mejoras Aplicadas a los Tests

Durante la primera pasada de mutación, se identificaron mutantes sobrevivientes que revelaron oportunidades de mejora en las aserciones de las pruebas unitarias. Se aplicaron los siguientes ajustes:

### Caso 1: `GeoBoundingBox` (Cobertura de mutación elevada de 79% al 100%)
- **Problema**: Las condiciones de borde para coordenadas extremas (`minLatitude <= MIN_LATITUDE` y `minLongitude < MIN_LONGITUDE`) no contaban con pruebas en los puntos exactos de polos (`±90.0°`) ni antimeridiano (`±180.0°`).
- **Mejora**: Se agregaron pruebas unitarias específicas (`exactPoleBoundariesTriggerFullLongitudeRange` y `exactAntimeridianBoundariesKeepExactCoordinatesWithoutOpeningFullRange`). Al validar los valores límites exactos, los 4 mutantes de frontera condicional fueron eliminados.

### Caso 2: `BikeService` (Capacidad en traslados y aserciones de creación)
- **Problema**:
  - En `transfer()`, la llamada a `ensureCapacity(destination)` sobrevivía porque ninguna prueba validaba el rechazo de transferencia hacia una estación completa.
  - En `create()`, los campos `model`, `stationId` y `purchaseDate` eran asignados a la entidad pero la prueba solo validaba `code` y `status`.
  - En `returnFromMaintenance()`, no se verificaba la persistencia del registro en `BikeStatusHistory`.
- **Mejora**:
  - Se agregó `rejectsTransferWhenDestinationStationIsFull()` verificando que se arroje `BusinessRuleException("...capacidad disponible...")`.
  - Se añadieron aserciones exhaustivas sobre `model()`, `purchaseDate()` y `stationId()`.
  - Se capturó y validó el objeto `BikeStatusHistory` persistido con `ArgumentCaptor`.

### Caso 3: `TripService` e `IncidentService` (Aserciones de estado persistido)
- **Problema**: Al iniciar un viaje (`startTrip`) o reportar una incidencia (`report`), si PIT eliminaba la llamada a `trip.setStatus(...)` o `incident.setStatus(...)`, la prueba pasaba porque solo se verificaba el mapeo del DTO de respuesta y no el estado guardado en el repositorio.
- **Mejora**: Se capturó la entidad guardada en el `tripRepository` e `incidentRepository` con `ArgumentCaptor` y se verificó explícitamente `assertThat(saved.getValue().getStatus()).isEqualTo(TripStatus.ACTIVE)` y `BikeIncidentStatus.OPEN`.

### Caso 4: `MaintenanceService` (Descripción y Resolución)
- **Problema**: Mutadores que eliminaban `setDescription(...)` y `setResolution(...)` sobrevivían porque los tests no validaban dichos campos en el DTO de respuesta.
- **Mejora**: Se incorporaron las aserciones sobre `description()` y `resolution()`.

### Caso 5: `BikeController` (Mutante de retorno de lista vacía)
- **Problema**: El test de `BikeController.history(id)` mockeaba una lista vacía `List.of()`, por lo que cuando PIT reemplazaba el retorno por `Collections.emptyList()`, la aserción `isEmpty()` seguía pasando.
- **Mejora**: Se mockeó un elemento representativo `BikeStatusHistoryResponse` y se validó con `containsExactly(historyItem)`, logrando 100% de Test Strength en la capa web.

### Caso 6: Mutante Equivalente Identificado en `EcobiciStationMapper`
- **Mutante**: `removed call to Station::setStatus` en `EcobiciStationMapper.java:42`.
- **Análisis**: En la entidad JPA `Station.java`, el campo `status` está inicializado por defecto con `private StationStatus status = StationStatus.ACTIVE;`. Por lo tanto, aunque el mapper no invoque explícitamente `station.setStatus(StationStatus.ACTIVE)`, la instancia creada ya posee ese valor. Es un **mutante equivalente** representativo, que no altera el resultado observable.

---

## 7. Instrucciones para Git

Los cambios se encuentran exclusivamente en la rama de trabajo **`test/MOV-048-tests-mutacion`**, listos para ser versionados y subidos a GitHub **sin hacer merge con `main`**:

```bash
# 1. Comprobar archivos modificados
git status

# 2. Agregar los cambios al stage
git add build.gradle docs/INFORME_TESTS_MUTACION.md src/test/java/

# 3. Crear el commit
git commit -m "test(mutation): configurar PIT y optimizar suite alcanzando 96% de mutation score"

# 4. Subir la rama a GitHub (sin mergear con main)
git push origin test/MOV-048-tests-mutacion
```
