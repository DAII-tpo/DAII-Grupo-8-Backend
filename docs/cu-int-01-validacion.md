# Validación y pendientes del CU-INT-01

Base revisada: PR #36, commit `fd3f23f071207163ad1270b96a3b85fb7ecff39d`.
Rama: `feat/cu-int-01-ruta-urbana`. Sin cambios a main, merges ni despliegues.

## Correcciones locales

- La consulta directa rechaza eventos terminados o sin fecha de finalización,
  además de los cancelados, espacios deshabilitados y eventos ausentes.
- El recálculo ante incidentes usa el radio del filtro configurado, en lugar de
  sumar siempre 300 m al radio de candidatas. La suma cubre por desigualdad
  triangular las estaciones candidatas potencialmente afectadas.
- El payload saliente accede a la estación dentro de una comprobación explícita
  de no nulidad, conservando los 12 campos del contrato y sus valores nulos.

## Tests agregados

Pruebas unitarias de handlers para los siete contratos conocidos, delegación al
outbox oficial, incidentes y fechas ISO/epoch, cierre idempotente, estados de
espacios, entradas desconocidas/IDs ausentes, recomendaciones/invalidez, scheduler,
aislamiento de fallos, radio configurable y conteo de descartes sin duplicados.
**Estos tests Java todavía no se compilaron ni ejecutaron en este entorno.**
El test del writer verifica delegación; no demuestra por sí solo persistencia
transaccional AFTER_COMMIT/REQUIRES_NEW.

## Ejecución

JDK 21 completo instalado y javac verificado. Desde la raíz:

```sh
export JAVA_HOME=/workspace/.setup/jdk/usr/lib/jvm/java-21-openjdk-amd64
export GRADLE_USER_HOME=/workspace/.setup/gradle
bash gradlew --no-daemon --max-workers=2 compileJava test jacocoTestReport
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 recommendation-service/.venv/bin/python -m pytest recommendation-service --cov=recommendation-service/app --cov=recommendation-service/training --cov-config=recommendation-service/.coveragerc --cov-report=xml:recommendation-service/coverage.xml
```

Java queda bloqueado al resolver kafka-avro-serializer 7.9.2 desde
packages.confluent.io (HTTP 403). No hay una cifra nueva de cobertura Java.
Python: 48 tests correctos, cobertura XML generada; advertencia de deprecación de
AnyIO/Starlette. MySQL local estaba disponible durante la preparación del entorno.

La API GitHub y SonarCloud también responden HTTP 403 por la política actual.
Se guardaron los dominios packages.confluent.io, api.github.com y sonarcloud.io
en el borrador del entorno. Aplicar esa configuración permite retomar diagnóstico,
compilación, tests y revisión del issue exacto de fiabilidad. No se confirma un
Quality Gate verde ni la calificación A de fiabilidad. No se desactivó ninguna
regla ni se agregaron exclusiones de cobertura.

## Integraciones y trabajo pendiente

Se conserva inbox/outbox y los contratos existentes. No se mezclaron las ramas
independientes de BI de viajes ni estaciones afectadas.

Falta verificar persistencia real AFTER_COMMIT/REQUIRES_NEW, cobertura Java >=80 %,
issue concreto de fiabilidad y Quality Gate remoto. La llegada de cancelación
antes de la publicación, resolución antes de creación y estado de espacio antes
del destino requieren una estrategia persistente de orden/tombstones; hoy no se
garantizan. Tampoco se verificó una actualización que mueva un incidente ni la
recuperación de invalidaciones cuyo outbox falle. No declarar el CU completo.

Espacios: publicación real y schema exacto de ReservaConfirmada. Reclamos y
Emergencias: disponibilidad para probar creación/resolución real. BI: aprobación
del contrato de estaciones afectadas, URLs de dashboards, permisos de iframe y
consumidor. No se enviaron mensajes al gateway compartido ni se registraron tipos.
