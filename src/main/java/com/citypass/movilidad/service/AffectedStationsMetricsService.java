package com.citypass.movilidad.service;

import com.citypass.movilidad.model.CityIncident;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.CityIncidentRepository;
import com.citypass.movilidad.repository.StationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Snapshot global de estaciones únicas afectadas por incidentes activos. */
@Service
public class AffectedStationsMetricsService {
    private final StationRepository stations;
    private final CityIncidentRepository incidents;
    private final int radiusMeters;

    public AffectedStationsMetricsService(StationRepository stations,
                                         CityIncidentRepository incidents,
                                         @Value("${movilidad.incidents.radius-meters:300}") int radiusMeters) {
        this.stations = stations;
        this.incidents = incidents;
        this.radiusMeters = radiusMeters;
    }

    @Transactional(readOnly = true)
    public Snapshot calculate() {
        List<CityIncident> active = incidents.findByActiveTrue();
        if (active.isEmpty()) return new Snapshot(0, 0);
        long affected = stations.findByStatusAndDeletedAtIsNull(StationStatus.ACTIVE).stream()
                .filter(station -> active.stream().anyMatch(incident -> isAffected(station, incident)))
                .count();
        return new Snapshot(Math.toIntExact(affected), active.size());
    }

    private boolean isAffected(Station station, CityIncident incident) {
        return GeoDistance.meters(station.getLatitude().doubleValue(), station.getLongitude().doubleValue(),
                incident.getLatitude().doubleValue(), incident.getLongitude().doubleValue()) <= radiusMeters;
    }

    public record Snapshot(int affectedStations, int activeIncidents) { }
}
