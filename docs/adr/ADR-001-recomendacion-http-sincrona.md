# ADR-001 — El servicio de recomendación se consume por HTTP síncrono

- **Estado:** Aceptado
- **Tickets:** MOV-041 (SCRUM-68) define el servicio; MOV-042 (SCRUM-69) implementa su consumo desde el backend
- **Relacionado:** ADR de stack de MOV-004 (Python + FastAPI para el componente de IA)

## Contexto

La recomendación de estaciones vive en un microservicio Python separado del backend transaccional
(decisión de MOV-004: el ecosistema de ML de Python es más maduro y no se mezclan dependencias de ciencia
de datos con Spring). Hay que decidir cómo expone su funcionalidad y cómo la consume el backend.

El ADR de MOV-004 menciona que el componente de IA es "consumido por eventos". Esa frase se pensó para la
**predicción de demanda** (procesos batch, sin un usuario esperando). La recomendación de estaciones es
distinta: el usuario pide "recomendame una estación ahora" y espera la respuesta en la misma pantalla.

Además, el event bus de Kafka (Event Gateway del Grupo 1) es el mecanismo para **comunicar eventos entre
módulos** del proyecto. El backend de Movilidad y su servicio de recomendación son parte del mismo módulo: no
hay otro módulo interesado en esta consulta.

## Alternativas

1. **Eventos vía el Event Gateway (Kafka) del Grupo 1.** Convierte una consulta interactiva en request/reply
   sobre un bus asíncrono pensado para integrar módulos: correlación de mensajes, timeouts propios y
   dependencia de un componente de otro equipo para una funcionalidad interna.
2. **El servicio Python lee MySQL directamente.** Evita enviar el snapshot, pero rompe el límite de dominio:
   la IA pasaría a conocer el esquema transaccional y se acoplaría a sus migraciones.
3. **HTTP síncrono, con el snapshot de disponibilidad en el request.** Patrón request/response natural para
   una consulta interactiva dentro del módulo.

## Decisión

Se adopta la **alternativa 3**. El servicio expone `POST /v1/recommendations` (documentado en OpenAPI en
`/docs`) y es **stateless respecto del dominio**: no tiene base de datos ni conoce entidades del backend. Recibe
en cada request la ubicación del usuario y el snapshot de estaciones candidatas con su disponibilidad actual.

Lineamientos para el consumidor (MOV-042):

- Enviar **solo estaciones habilitadas**. Una estación deshabilitada (obras, emergencias, fuerza mayor) nunca
  debe llegar al modelo; el servicio no sabe distinguirlas.
- Aplicar **timeouts cortos** y un **fallback propio** (por ejemplo, la estación más cercana con disponibilidad)
  para que una falla del componente de IA nunca se propague como error al usuario.
- Validar que la estación devuelta pertenezca al snapshot enviado.

## Consecuencias

- **Positivas:** latencia baja y predecible; contrato simple y documentado; el filtro de estaciones
  deshabilitadas queda en el backend; el servicio se puede testear y reentrenar sin base de datos.
- **Negativas:** acoplamiento temporal (si el servicio no responde, el consumidor usa su fallback, que no es
  IA); el request crece con la cantidad de candidatas (máximo 200 por request).
- **Evolución:** la futura predicción por históricos (batch) sí puede usar el event bus; eso sería un ADR
  aparte y no cambia este contrato.
