package com.citypass.movilidad.service;

import com.citypass.movilidad.repository.EspaciosEventCursorRepository;
import com.citypass.movilidad.repository.SpaceReservationRepository;
import com.citypass.movilidad.repository.SpaceReservationRepository.Reservation;
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
    private static final String SPACE_STATE_TYPE = "SPACE";
    private static final String SPACE_ID_FIELD = "espacioId";
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
    private final EspaciosEventCursorRepository cursors;
    private final SpaceReservationRepository reservations;

    public InboundEventService(CulturalDestinationRepository destinationRepository,
                               CityIncidentRepository incidentRepository,
                               ApplicationEventPublisher domainEvents,
                               InboundEntityStateRepository entityStates,
                               DestinationRecommendationJobRepository jobs,
                               EspaciosEventCursorRepository cursors,
                               SpaceReservationRepository reservations) {
        this.destinationRepository = destinationRepository;
        this.incidentRepository = incidentRepository;
        this.domainEvents = domainEvents;
        this.entityStates = entityStates;
        this.jobs = jobs;
        this.cursors = cursors;
        this.reservations = reservations;
    }

    /** Entrada local para pruebas; el handler Kafka utiliza la sobrecarga con metadata.receivedAt. */
    public void handle(String eventType, JsonNode data) {
        handle(eventType, data, Instant.now());
    }

    /** El handler Kafka pasa la hora del gateway, no la hora local de procesamiento. */
    public void handle(String eventType, JsonNode data, Instant receivedAt) {
        java.util.Objects.requireNonNull(receivedAt, "metadata.receivedAt es obligatorio");
        if (eventType.startsWith(ESPACIOS)) cursors.lock();
        switch (eventType) {
            case ESPACIOS + "EventoComunitarioPublicado" -> upsertDestination(data, receivedAt);
            case ESPACIOS + "EventoCancelado" -> cancelDestination(data, receivedAt);
            case ESPACIOS + "EspacioEstadoCambiado" -> updateSpaceStatus(data, receivedAt);
            case ESPACIOS + "ReservaConfirmada" -> confirmReservation(data, receivedAt);
            case RECLAMOS + "ReclamoCreado", RECLAMOS + "ReclamoCreado.v3" ->
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

    private void upsertDestination(JsonNode data, Instant receivedAt) {
        String eventId = text(data, "eventoId");
        boolean current = cursors.accept(CULTURAL_EVENT_STATE_TYPE, eventId, receivedAt);
        var existing = destinationRepository.findById(eventId);
        // Si cancelación y creación llegaron cruzadas, conservar el primer snapshot como cancelado
        // permite devolver la invalidación sin resucitarlo ni reemplazar una proyección vigente.
        if (!current && (existing.isPresent()
                || !entityStates.hasState(CULTURAL_EVENT_STATE_TYPE, eventId, CANCELLED_STATE))) return;
        CulturalDestination destination = existing
                .orElseGet(() -> CulturalDestination.builder().eventId(eventId).build());

        destination.setSpaceId(text(data, SPACE_ID_FIELD));
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
        // El total de inscriptos proviene únicamente de este snapshot, no de reservas.
        String state = optionalText(data, "estado");
        updateDemand(data, destination, state);
        boolean knownState = state != null
                && Set.of("ACTIVE", "ACTIVE_FULL", "SUSPENDED", CANCELLED_STATE).contains(state);
        destination.setEventStatus(knownState ? state : "UNKNOWN");
        if (CANCELLED_STATE.equals(state)) {
            entityStates.remember(CULTURAL_EVENT_STATE_TYPE, eventId, CANCELLED_STATE);
        }
        destination.setCancelled(destination.isCancelled()
                || CANCELLED_STATE.equals(state)
                || entityStates.hasState(CULTURAL_EVENT_STATE_TYPE, eventId, CANCELLED_STATE));
        if (destination.isCancelled()) destination.setEventStatus(CANCELLED_STATE);
        destination.setSpaceEnabled(!entityStates.hasState(SPACE_STATE_TYPE, destination.getSpaceId(), "DISABLED"));
        destination.setUpdatedAt(Instant.now());
        destination.setRecommendationToken(jobs.request(eventId));
        destinationRepository.save(destination);
        domainEvents.publishEvent(new CulturalDestinationChanged(eventId));
    }

    /** Una actualización explícita puede reducir inscriptos; un campo ausente conserva el valor. */
    private static void updateDemand(JsonNode data, CulturalDestination destination, String state) {
        JsonNode registrations = data.path("inscriptos");
        if (!registrations.isMissingNode()) {
            if (!registrations.isIntegralNumber() || !registrations.canConvertToInt() || registrations.intValue() < 0) {
                throw new IllegalArgumentException("inscriptos debe ser un entero no negativo");
            }
            destination.setRegistrations(registrations.intValue());
        }
        if (state != null) destination.setFullCapacity("ACTIVE_FULL".equals(state));
    }

    private void cancelDestination(JsonNode data, Instant receivedAt) {
        String eventId = text(data, "eventoId");
        // La cancelación es definitiva incluso si llegó con fecha anterior al último snapshot.
        cursors.accept(CULTURAL_EVENT_STATE_TYPE, eventId, receivedAt);
        entityStates.remember(CULTURAL_EVENT_STATE_TYPE, eventId, CANCELLED_STATE);
        destinationRepository.findById(eventId).ifPresent(destination -> {
            if (destination.isCancelled()) return;
            destination.setCancelled(true);
            destination.setEventStatus(CANCELLED_STATE);
            destination.setUpdatedAt(Instant.now());
            destination.setRecommendationToken(jobs.request(destination.getEventId()));
            destinationRepository.save(destination);
            domainEvents.publishEvent(new CulturalDestinationChanged(destination.getEventId()));
        });
    }

    private void updateSpaceStatus(JsonNode data, Instant receivedAt) {
        String status = text(data, NEW_STATUS_FIELD);
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new IllegalArgumentException("Estado de espacio desconocido: " + status);
        }
        boolean enabled = "ENABLED".equals(status);
        String spaceId = text(data, SPACE_ID_FIELD);
        if (!cursors.accept(SPACE_STATE_TYPE, spaceId, receivedAt)) return;
        entityStates.remember(SPACE_STATE_TYPE, spaceId, status);
        for (CulturalDestination destination : destinationRepository.findBySpaceId(spaceId)) {
            destination.setSpaceEnabled(enabled);
            destination.setUpdatedAt(Instant.now());
            destination.setRecommendationToken(jobs.request(destination.getEventId()));
            destinationRepository.save(destination);
            domainEvents.publishEvent(new CulturalDestinationChanged(destination.getEventId()));
        }
    }

    private void confirmReservation(JsonNode data, Instant receivedAt) {
        String id = text(data, "reservaId");
        String type = text(data, "tipoReserva");
        if (!"EVENTO".equals(type) && !"ESPACIO".equals(type)) {
            throw new IllegalArgumentException("tipoReserva debe ser EVENTO o ESPACIO");
        }
        String resourceId = text(data, "recursoId");
        String spaceId = text(data, SPACE_ID_FIELD);
        if ("ESPACIO".equals(type) && !resourceId.equals(spaceId)) {
            throw new IllegalArgumentException("recursoId debe identificar el espacio reservado");
        }
        JsonNode people = data.path("cantidadPersonas");
        if (!people.isIntegralNumber() || !people.canConvertToInt() || people.intValue() < 0) {
            throw new IllegalArgumentException("cantidadPersonas debe ser un entero no negativo");
        }
        Instant start = iso(data, "inicio");
        Instant end = iso(data, "fin");
        if (start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException("inicio y fin deben definir un intervalo válido");
        }
        if (!cursors.accept("RESERVATION", id, receivedAt)) return;
        reservations.save(new Reservation(id, type, resourceId, spaceId, people.intValue(), start, end));
        // ESPACIO aporta demanda por lugar/horario, pero no contiene coordenadas ni un evento cultural.
        // Se conserva sin inventar un destino; EVENTO reutiliza el snapshot conocido y nunca suma inscriptos.
        if ("EVENTO".equals(type)) {
            destinationRepository.findById(resourceId).ifPresent(destination -> {
                if (!spaceId.equals(destination.getSpaceId())) {
                    throw new IllegalArgumentException("La reserva y el evento pertenecen a espacios diferentes");
                }
                destination.setRecommendationToken(jobs.request(resourceId));
                destinationRepository.save(destination);
                domainEvents.publishEvent(new CulturalDestinationChanged(resourceId));
            });
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
        incident.setCorrelationId(optionalText(data, "correlationId"));
        String roadIncidentId = "INCIDENTE_VIAL".equals(optionalText(data, "causaTipo"))
                ? text(data, "causaId") : null;
        incident.setRoadIncidentId(roadIncidentId);
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
