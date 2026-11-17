package com.citypass.movilidad.service;

import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.exception.BusinessRuleException;
import com.citypass.movilidad.model.CityIncident;
import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.model.enums.IncidentSource;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.CityIncidentRepository;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.StationAffectationRepository;
import com.citypass.movilidad.repository.StationAffectationRepository.Affectation;
import com.citypass.movilidad.repository.StationAffectationRepository.Key;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reconcilia estación/causa y publica transiciones, nunca incrementos ni snapshots periódicos.
 *
 * <p>Las afectaciones por alerta climática las abren y cierran los eventos de la alerta
 * ({@link #blockForClimateAlert}, {@link #restoreClimateAlert}); el reconciliador nunca las crea y solo restaura
 * las que quedaron abiertas para una alerta ya FINALIZADA (red de seguridad).
 */
@Service
public class AffectedStationsService {
    private static final String EVENTO_PUBLICO = "EVENTO_PUBLICO";
    private static final String EVENTO_CLIMATICO = "EVENTO_CLIMATICO";
    private static final String RECOMENDACION_RESTRINGIDA = "RECOMENDACION_RESTRINGIDA";
    private static final String BLOQUEADA = "BLOQUEADA";
    private static final String MOVILIDAD = "MOVILIDAD";
    private final StationRepository stations;
    private final CityIncidentRepository incidents;
    private final StationAffectationRepository affectations;
    private final EventPublisher events;
    private final int radiusMeters;

    public AffectedStationsService(StationRepository stations, CityIncidentRepository incidents,
                                  StationAffectationRepository affectations, EventPublisher events,
                                  @Value("${movilidad.incidents.radius-meters:300}") int radiusMeters) {
        this.stations = stations;
        this.incidents = incidents;
        this.affectations = affectations;
        this.events = events;
        this.radiusMeters = radiusMeters;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void synchronize() {
        // Serializa el timer y los listeners, incluso entre instancias del backend.
        affectations.lock();
        Map<Key, Candidate> desired = new LinkedHashMap<>();
        var active = incidents.findByActiveTrue();
        if (!active.isEmpty()) {
            for (Station station : stations.findByStatusAndDeletedAtIsNull(StationStatus.ACTIVE)) {
                for (CityIncident incident : active) {
                    if (GeoDistance.meters(station.getLatitude().doubleValue(), station.getLongitude().doubleValue(),
                            incident.getLatitude().doubleValue(), incident.getLongitude().doubleValue()) <= radiusMeters) {
                        Candidate candidate = new Candidate(station, incident);
                        desired.putIfAbsent(new Key(station.getId(), candidate.causeType(), candidate.causeId()), candidate);
                    }
                }
            }
        }
        Instant now = Instant.now();
        var openFestival = affectations.openFestivalAffectations(now.toEpochMilli());
        var openClimate = affectations.openClimateAffectations();
        for (Affectation existing : affectations.findAll()) {
            boolean keep = switch (existing.causeType()) {
                case EVENTO_PUBLICO -> openFestival.contains(existing.id());
                case EVENTO_CLIMATICO -> openClimate.contains(existing.id());
                default -> desired.remove(existing.key()) != null;
            };
            if (!keep) restore(existing, now);
        }
        desired.values().forEach(candidate -> affect(candidate, now));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registerFestivalTransfer(long taskId, Station station) {
        affectations.lock();
        Instant now = Instant.now();
        var task = affectations.findFestivalTransfer(taskId, station.getId(), now.toEpochMilli())
                .orElseThrow(() -> new BusinessRuleException(
                        "La tarea de reabastecimiento debe estar pendiente y corresponder a la estación de destino."));
        if (affectations.hasFestivalAffectation(station.getId(), task.activityId())) return;

        Instant start = task.startMillis() == null ? now : Instant.ofEpochMilli(task.startMillis());
        Instant from = start.isAfter(now) ? start : now;
        var affectation = new Affectation(UUID.randomUUID().toString(), station.getId(), task.activityId(),
                EVENTO_PUBLICO, MOVILIDAD, task.correlationId());
        publishAffectation(affectation, station, RECOMENDACION_RESTRINGIDA, from,
                Instant.ofEpochMilli(task.deadlineMillis()), now, AggregateType.INCIDENT);
    }

    /**
     * Abre una afectación BLOQUEADA por estación ACTIVE. Es preventiva: afectacionDesde es la vigencia de la
     * alerta aunque todavía no empiece. Idempotente por (estación, EVENTO_CLIMATICO, alertaId), así que se puede
     * volver a llamar al activar la alerta sin publicar duplicados. Devuelve cuántas abrió.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int blockForClimateAlert(ClimateAlert alert, List<Station> stationsInArea, Instant now) {
        affectations.lock();
        String alertaId = alert.getExternalAlertId();
        int opened = 0;
        for (Station station : stationsInArea) {
            if (station.getStatus() != StationStatus.ACTIVE || station.getDeletedAt() != null
                    || affectations.exists(new Key(station.getId(), EVENTO_CLIMATICO, alertaId))) {
                continue;
            }
            var affectation = new Affectation(UUID.randomUUID().toString(), station.getId(), alertaId,
                    EVENTO_CLIMATICO, MOVILIDAD, alertaId);
            publishAffectation(affectation, station, BLOQUEADA, alert.getValidFrom(), alert.getValidUntil(), now,
                    AggregateType.CLIMATE_ALERT);
            opened++;
        }
        return opened;
    }

    /** Cierra todas las afectaciones abiertas de la alerta (una por estación). Devuelve cuántas cerró. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int restoreClimateAlert(String alertaId, Instant restoredAt) {
        affectations.lock();
        List<Affectation> open = affectations.findByCause(EVENTO_CLIMATICO, alertaId);
        open.forEach(affectation -> restore(affectation, restoredAt));
        return open.size();
    }

    private void affect(Candidate candidate, Instant now) {
        Station station = candidate.station();
        CityIncident incident = candidate.incident();
        String correlation = incident.getCorrelationId();
        if (correlation == null || correlation.isBlank()) {
            correlation = incident.getSource().name() + "-" + incident.getExternalId();
        }
        Affectation a = new Affectation(UUID.randomUUID().toString(), station.getId(),
                candidate.causeId(), candidate.causeType(), incident.getSource().name(), correlation);
        publishAffectation(a, station, RECOMENDACION_RESTRINGIDA, now, null, now, AggregateType.INCIDENT);
    }

    private void publishAffectation(Affectation a, Station station, String tipoAfectacion,
                                    Instant from, Instant until, Instant now, AggregateType aggregate) {
        affectations.save(a);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("afectacionId", a.id());
        data.put("estacionId", Long.toString(a.stationId()));
        data.put("latitud", station.getLatitude().doubleValue());
        data.put("longitud", station.getLongitude().doubleValue());
        // CU-INT-01: RECOMENDACION_RESTRINGIDA (no altera capacidad ni estado físico; extensión pendiente de
        // aceptación de BI). Alerta climática: BLOQUEADA, el alquiler se bloquea sin tocar station.status.
        data.put("tipoAfectacion", tipoAfectacion);
        data.put("afectacionDesde", from.toString());
        data.put("afectacionHastaEstimada", until == null ? null : until.toString());
        data.put("causaId", a.causeId());
        data.put("causaTipo", a.causeType());
        data.put("moduloOrigen", a.originModule());
        data.put("correlationId", a.correlationId());
        data.put("occurredAt", now.toString());
        events.publish("EstacionUrbanaAfectada", aggregate, a.id(), data);
    }

    private void restore(Affectation a, Instant now) {
        AggregateType aggregate = EVENTO_CLIMATICO.equals(a.causeType())
                ? AggregateType.CLIMATE_ALERT : AggregateType.INCIDENT;
        events.publish("EstacionUrbanaRestaurada", aggregate, a.id(), Map.of(
                "afectacionId", a.id(), "estacionId", Long.toString(a.stationId()),
                "correlationId", a.correlationId(), "restauradaAt", now.toString()));
        affectations.delete(a.id());
    }

    private record Candidate(Station station, CityIncident incident) {
        String causeId() {
            return incident.getRoadIncidentId() == null ? incident.getExternalId() : incident.getRoadIncidentId();
        }
        String causeType() {
            if (incident.getRoadIncidentId() != null) return "INCIDENTE_VIAL";
            return incident.getSource() == IncidentSource.RECLAMOS ? "RECLAMO" : "EMERGENCIA";
        }
    }
}
