package com.citypass.movilidad.service;

import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.model.CityIncident;
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
import java.util.Map;
import java.util.UUID;

/** Reconcilia estación/causa y publica transiciones, nunca incrementos ni snapshots periódicos. */
@Service
public class AffectedStationsService {
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
        for (Affectation existing : affectations.findAll()) {
            if (desired.remove(existing.key()) == null) restore(existing, now);
        }
        desired.values().forEach(candidate -> affect(candidate, now));
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
        affectations.save(a);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("afectacionId", a.id());
        data.put("estacionId", Long.toString(a.stationId()));
        data.put("latitud", station.getLatitude().doubleValue());
        data.put("longitud", station.getLongitude().doubleValue());
        // El CU restringe recomendaciones; no altera capacidad ni estado físico de la estación.
        // Extensión pendiente de aceptación de BI; no equivale funcionalmente a BLOQUEADA.
        data.put("tipoAfectacion", "RECOMENDACION_RESTRINGIDA");
        data.put("afectacionDesde", now.toString());
        data.put("afectacionHastaEstimada", null);
        data.put("causaId", a.causeId());
        data.put("causaTipo", a.causeType());
        data.put("moduloOrigen", a.originModule());
        data.put("correlationId", a.correlationId());
        data.put("occurredAt", now.toString());
        events.publish("EstacionUrbanaAfectada", AggregateType.INCIDENT, a.id(), data);
    }

    private void restore(Affectation a, Instant now) {
        events.publish("EstacionUrbanaRestaurada", AggregateType.INCIDENT, a.id(), Map.of(
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
