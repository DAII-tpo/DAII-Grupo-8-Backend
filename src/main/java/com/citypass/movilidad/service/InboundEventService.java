package com.citypass.movilidad.service;

import com.citypass.movilidad.model.CityIncident;
import com.citypass.movilidad.model.CulturalDestination;
import com.citypass.movilidad.model.enums.IncidentSource;
import com.citypass.movilidad.repository.CityIncidentRepository;
import com.citypass.movilidad.repository.InboundEntityStateRepository;
import com.citypass.movilidad.repository.DestinationRecommendationJobRepository;
import com.citypass.movilidad.repository.CulturalDestinationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * Procesa eventos de Kafka vía EventHandler (CU-INT-01).
 *
 * EventDispatcher registra event_inbox de forma transaccional y evita reentregas.
 */
@Service
@Slf4j
public class InboundEventService {

    static final String ESPACIOS = "com.citypass.espacios.";
    static final String RECLAMOS = "com.citypass.reclamos.";
    static final String EMERGENCIAS = "com.citypass.emergencias.";

    private static final String CANCELLED_STATE = "CANCELLED";
    private static final String CULTURAL_EVENT_STATE_TYPE = "CULTURAL_EVENT";
    private static final String LATITUDE_FIELD = "latitud";
    private static final String NEW_STATUS_FIELD = "estadoNuevo";

    /** Estados finales: el incidente deja de afectar la circulación. */
    private static final Set<String> CLAIM_CLOSED = Set.of("RESUELTO", "RECHAZADO", "CERRADO");
    private static final Set<String> EMERGENCY_CLOSED = Set.of("RESUELTA", "CERRADA", "DESCARTADA", "CANCELADA");

    private final CulturalDestinationRepository destinationRepository;
    private final CityIncidentRepository incidentRepository;
    private final ApplicationEventPublisher domainEvents;
    private final InboundEntityStateRepository entityStates;
    private final DestinationRecommendationJobRepository jobs;

    public InboundEventService(CulturalDestinationRepository destinationRepository,
                               CityIncidentRepository incidentRepository,
                               ApplicationEventPublisher domainEvents,
                               InboundEntityStateRepository entityStates,
                               DestinationRecommendationJobRepository jobs) {
        this.destinationRepository = destinationRepository;
        this.incidentRepository = incidentRepository;
        this.domainEvents = domainEvents;
        this.entityStates = entityStates;
        this.jobs = jobs;
    }

    /** Invocado por el dispatcher transaccional Kafka: la idempotencia la maneja event_inbox. */
    public void handle(String eventType, JsonNode data) {
        switch (eventType) {
            case ESPACIOS + "EventoComunitarioPublicado" -> upsertDestination(data);
            case ESPACIOS + "EventoCancelado" -> cancelDestination(data);
            case ESPACIOS + "EspacioEstadoCambiado" -> updateSpaceStatus(data);
            case RECLAMOS + "ReclamoCreado" ->
                    openIncident(IncidentSource.RECLAMOS, text(data, "reclamoId"), data, epochMillis(data, "createdAt"));
            case RECLAMOS + "ReclamoActualizado" ->
                    closeIfFinal(IncidentSource.RECLAMOS, text(data, "reclamoId"), text(data, NEW_STATUS_FIELD), CLAIM_CLOSED);
            case EMERGENCIAS + "EmergenciaCreada" ->
                    openIncident(IncidentSource.EMERGENCIAS, text(data, "emergenciaId"), data, iso(data, "createdAt"));
            case EMERGENCIAS + "EmergenciaActualizada" ->
                    closeIfFinal(IncidentSource.EMERGENCIAS, text(data, "emergenciaId"), text(data, NEW_STATUS_FIELD), EMERGENCY_CLOSED);
            default -> log.info("Tipo de evento sin manejo en Movilidad: {}", eventType);
        }
    }

    // --- Destinos (Espacios Públicos) ---------------------------------------

    private void upsertDestination(JsonNode data) {
        String eventId = text(data, "eventoId");
        CulturalDestination destination = destinationRepository.findById(eventId)
                .orElseGet(() -> CulturalDestination.builder().eventId(eventId).build());

        destination.setSpaceId(text(data, "espacioId"));
        destination.setTitle(text(data, "titulo"));
        destination.setZone(optionalText(data, "zona"));
        destination.setAddress(optionalText(data, "direccion"));
        destination.setLatitude(decimal(data, LATITUDE_FIELD));
        destination.setLongitude(decimal(data, "longitud"));
        destination.setStartsAt(iso(data, "inicio"));
        destination.setEndsAt(iso(data, "fin"));
        // Espacios enviará ambos campos con el mismo valor. No convertir un faltante en cero.
        JsonNode capacity = data.path("cupoMaximo");
        if (!capacity.isIntegralNumber()) capacity = data.path("capacidad");
        if (!capacity.isIntegralNumber()) {
            throw new IllegalArgumentException("Falta cupoMaximo/capacidad en EventoComunitarioPublicado");
        }
        if (!capacity.canConvertToInt() || capacity.intValue() < 0) {
            throw new IllegalArgumentException("cupoMaximo/capacidad debe ser un entero no negativo");
        }
        if (destination.getStartsAt() == null || destination.getEndsAt() == null
                || !destination.getEndsAt().isAfter(destination.getStartsAt())) {
            throw new IllegalArgumentException("inicio y fin deben definir un intervalo válido");
        }
        destination.setMaxCapacity(capacity.intValue());
        // El registro incremental por ReservaConfirmada se implementa cuando Espacios envíe su contrato.
        destination.setRegistrations(Math.max(destination.getRegistrations(), data.path("inscriptos").asInt(0)));
        if (CANCELLED_STATE.equals(optionalText(data, "estado"))) {
            entityStates.remember(CULTURAL_EVENT_STATE_TYPE, eventId, CANCELLED_STATE);
        }
        destination.setCancelled(destination.isCancelled()
                || CANCELLED_STATE.equals(optionalText(data, "estado"))
                || entityStates.hasState(CULTURAL_EVENT_STATE_TYPE, eventId, CANCELLED_STATE));
        destination.setSpaceEnabled(!entityStates.hasState("SPACE", destination.getSpaceId(), "DISABLED"));
        destination.setUpdatedAt(Instant.now());
        destinationRepository.save(destination);
        jobs.request(eventId);
        domainEvents.publishEvent(new CulturalDestinationChanged(eventId));
    }

    private void cancelDestination(JsonNode data) {
        String eventId = text(data, "eventoId");
        entityStates.remember(CULTURAL_EVENT_STATE_TYPE, eventId, CANCELLED_STATE);
        destinationRepository.findById(eventId).ifPresent(destination -> {
            if (destination.isCancelled()) return;
            destination.setCancelled(true);
            destination.setUpdatedAt(Instant.now());
            destinationRepository.save(destination);
            jobs.request(destination.getEventId());
            domainEvents.publishEvent(new CulturalDestinationChanged(destination.getEventId()));
        });
    }

    private void updateSpaceStatus(JsonNode data) {
        String status = text(data, NEW_STATUS_FIELD);
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new IllegalArgumentException("Estado de espacio desconocido: " + status);
        }
        boolean enabled = "ENABLED".equals(status);
        String spaceId = text(data, "espacioId");
        entityStates.remember("SPACE", spaceId, status);
        for (CulturalDestination destination : destinationRepository.findBySpaceId(spaceId)) {
            destination.setSpaceEnabled(enabled);
            destination.setUpdatedAt(Instant.now());
            destinationRepository.save(destination);
            jobs.request(destination.getEventId());
            domainEvents.publishEvent(new CulturalDestinationChanged(destination.getEventId()));
        }
    }

    // --- Incidentes (Reclamos y Emergencias) --------------------------------

    private void openIncident(IncidentSource source, String externalId, JsonNode data, Instant reportedAt) {
        if (entityStates.hasState(source.name(), externalId, "CLOSED")) return;
        BigDecimal latitude = decimal(data, LATITUDE_FIELD);
        BigDecimal longitude = decimal(data, "longitud");

        // Solo cuentan los que cortan la circulación y dicen dónde ocurren.
        if (!data.path("afectaCirculacion").asBoolean(false) || latitude == null || longitude == null) {
            return;
        }

        CityIncident incident = incidentRepository.findBySourceAndExternalId(source, externalId)
                .orElseGet(() -> CityIncident.builder().source(source).externalId(externalId).build());
        incident.setCategory(text(data, "categoria"));
        incident.setLatitude(latitude);
        incident.setLongitude(longitude);
        incident.setActive(true);
        incident.setReportedAt(reportedAt == null ? Instant.now() : reportedAt);
        incident.setUpdatedAt(Instant.now());
        incidentRepository.save(incident);
        domainEvents.publishEvent(new TrafficIncidentChanged(latitude.doubleValue(), longitude.doubleValue()));
    }

    private void closeIfFinal(IncidentSource source, String externalId, String newStatus, Set<String> finalStates) {
        if (newStatus == null || !finalStates.contains(newStatus)) {
            return;
        }
        entityStates.remember(source.name(), externalId, "CLOSED");
        incidentRepository.findBySourceAndExternalId(source, externalId).ifPresent(incident -> {
            if (!incident.isActive()) return;
            incident.setActive(false);
            incident.setUpdatedAt(Instant.now());
            incidentRepository.save(incident);
            domainEvents.publishEvent(new TrafficIncidentChanged(
                    incident.getLatitude().doubleValue(), incident.getLongitude().doubleValue()));
        });
    }

    // --- Lectura del payload -------------------------------------------------

    private static String text(JsonNode data, String field) {
        String value = optionalText(data, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Falta el campo obligatorio '" + field + "'");
        }
        return value;
    }

    private static String optionalText(JsonNode data, String field) {
        JsonNode node = data.path(field);
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isTextual()) throw new IllegalArgumentException("El campo '" + field + "' debe ser texto");
        return node.textValue();
    }

    private static BigDecimal decimal(JsonNode data, String field) {
        JsonNode node = data.path(field);
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isNumber()) throw new IllegalArgumentException("El campo '" + field + "' debe ser numérico");
        BigDecimal value = node.decimalValue();
        int limit = LATITUDE_FIELD.equals(field) ? 90 : 180;
        if (value.abs().compareTo(BigDecimal.valueOf(limit)) > 0) {
            throw new IllegalArgumentException("Coordenada fuera de rango: " + field);
        }
        return value;
    }

    private static Instant iso(JsonNode data, String field) {
        String value = optionalText(data, field);
        return value == null ? null : Instant.parse(value);
    }

    private static Instant epochMillis(JsonNode data, String field) {
        JsonNode node = data.path(field);
        return node.isNumber() ? Instant.ofEpochMilli(node.asLong()) : null;
    }
}
