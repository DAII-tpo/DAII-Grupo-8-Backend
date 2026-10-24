package com.citypass.movilidad.service;

import com.citypass.movilidad.model.CityIncident;
import com.citypass.movilidad.model.CulturalDestination;
import com.citypass.movilidad.model.InboundEvent;
import com.citypass.movilidad.model.enums.IncidentSource;
import com.citypass.movilidad.repository.CityIncidentRepository;
import com.citypass.movilidad.repository.CulturalDestinationRepository;
import com.citypass.movilidad.repository.InboundEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * Procesa los eventos que llegan por webhook desde el gateway (CU-INT-01).
 *
 * El sobre es el del gateway: {@code {"metadata": {eventId, eventType, ...}, "data": {...}}}.
 * Todo corre en una transacción: si algo falla, tampoco queda registrado el eventId,
 * el webhook responde error y el dispatcher del Grupo 1 lo reintenta.
 */
@Service
@Slf4j
public class InboundEventService {

    static final String ESPACIOS = "com.citypass.espacios.";
    static final String RECLAMOS = "com.citypass.reclamos.";
    static final String EMERGENCIAS = "com.citypass.emergencias.";

    /** Estados finales: el incidente deja de afectar la circulación. */
    private static final Set<String> CLAIM_CLOSED = Set.of("RESUELTO", "RECHAZADO", "CERRADO");
    private static final Set<String> EMERGENCY_CLOSED = Set.of("RESUELTA", "CERRADA", "DESCARTADA", "CANCELADA");

    private final InboundEventRepository inboundEventRepository;
    private final CulturalDestinationRepository destinationRepository;
    private final CityIncidentRepository incidentRepository;

    public InboundEventService(InboundEventRepository inboundEventRepository,
                               CulturalDestinationRepository destinationRepository,
                               CityIncidentRepository incidentRepository) {
        this.inboundEventRepository = inboundEventRepository;
        this.destinationRepository = destinationRepository;
        this.incidentRepository = incidentRepository;
    }

    /** @return false si el evento ya se había procesado (reentrega del gateway). */
    @Transactional
    public boolean handle(JsonNode envelope) {
        JsonNode metadata = envelope.path("metadata");
        String eventId = metadata.path("eventId").asText(null);
        String eventType = metadata.path("eventType").asText(null);
        JsonNode data = envelope.path("data");

        if (eventId == null || eventType == null || data.isMissingNode()) {
            throw new IllegalArgumentException("El evento no tiene metadata.eventId, metadata.eventType o data");
        }

        if (inboundEventRepository.existsById(eventId)) {
            log.debug("Evento {} ya procesado, se ignora la reentrega", eventId);
            return false;
        }
        inboundEventRepository.save(new InboundEvent(eventId, eventType, null));

        switch (eventType) {
            case ESPACIOS + "EventoComunitarioPublicado" -> upsertDestination(data);
            case ESPACIOS + "EventoCancelado" -> cancelDestination(data);
            case ESPACIOS + "EspacioEstadoCambiado" -> updateSpaceStatus(data);
            case ESPACIOS + "ReservaCreada" -> addRegistration(data);
            case RECLAMOS + "ReclamoCreado" ->
                    openIncident(IncidentSource.RECLAMOS, text(data, "reclamoId"), data, epochMillis(data, "createdAt"));
            case RECLAMOS + "ReclamoActualizado" ->
                    closeIfFinal(IncidentSource.RECLAMOS, text(data, "reclamoId"), text(data, "estadoNuevo"), CLAIM_CLOSED);
            case EMERGENCIAS + "EmergenciaCreada" ->
                    openIncident(IncidentSource.EMERGENCIAS, text(data, "emergenciaId"), data, iso(data, "createdAt"));
            case EMERGENCIAS + "EmergenciaActualizada" ->
                    closeIfFinal(IncidentSource.EMERGENCIAS, text(data, "emergenciaId"), text(data, "estadoNuevo"), EMERGENCY_CLOSED);
            default -> log.info("Tipo de evento sin manejo en Movilidad: {}", eventType);
        }
        return true;
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
        destination.setLatitude(decimal(data, "latitud"));
        destination.setLongitude(decimal(data, "longitud"));
        destination.setStartsAt(iso(data, "inicio"));
        destination.setEndsAt(iso(data, "fin"));
        destination.setMaxCapacity(data.path("cupoMaximo").asInt());
        // Las inscripciones que llegaron antes que el evento (por orden de entrega) no se pisan.
        destination.setRegistrations(Math.max(destination.getRegistrations(), data.path("inscriptos").asInt(0)));
        destination.setCancelled("CANCELLED".equals(optionalText(data, "estado")));
        destination.setUpdatedAt(Instant.now());
        destinationRepository.save(destination);
    }

    private void cancelDestination(JsonNode data) {
        destinationRepository.findById(text(data, "eventoId")).ifPresent(destination -> {
            destination.setCancelled(true);
            destination.setUpdatedAt(Instant.now());
            destinationRepository.save(destination);
        });
    }

    private void updateSpaceStatus(JsonNode data) {
        boolean enabled = "ENABLED".equals(text(data, "estadoNuevo"));
        for (CulturalDestination destination : destinationRepository.findBySpaceId(text(data, "espacioId"))) {
            destination.setSpaceEnabled(enabled);
            destination.setUpdatedAt(Instant.now());
            destinationRepository.save(destination);
        }
    }

    private void addRegistration(JsonNode data) {
        // Solo las inscripciones a eventos suman demanda; las reservas de espacios no son destinos.
        if (!"EVENTO".equals(optionalText(data, "tipoReserva"))) {
            return;
        }
        destinationRepository.findById(text(data, "recursoId")).ifPresent(destination -> {
            destination.setRegistrations(destination.getRegistrations() + data.path("cantidadPersonas").asInt(1));
            destination.setUpdatedAt(Instant.now());
            destinationRepository.save(destination);
        });
    }

    // --- Incidentes (Reclamos y Emergencias) --------------------------------

    private void openIncident(IncidentSource source, String externalId, JsonNode data, Instant reportedAt) {
        BigDecimal latitude = decimal(data, "latitud");
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
    }

    private void closeIfFinal(IncidentSource source, String externalId, String newStatus, Set<String> finalStates) {
        if (newStatus == null || !finalStates.contains(newStatus)) {
            return;
        }
        incidentRepository.findBySourceAndExternalId(source, externalId).ifPresent(incident -> {
            incident.setActive(false);
            incident.setUpdatedAt(Instant.now());
            incidentRepository.save(incident);
        });
    }

    // --- Lectura del payload -------------------------------------------------

    private static String text(JsonNode data, String field) {
        String value = optionalText(data, field);
        if (value == null) {
            throw new IllegalArgumentException("Falta el campo obligatorio '" + field + "'");
        }
        return value;
    }

    private static String optionalText(JsonNode data, String field) {
        JsonNode node = data.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static BigDecimal decimal(JsonNode data, String field) {
        JsonNode node = data.path(field);
        return node.isNumber() ? node.decimalValue() : null;
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
