package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.NearbyStationResponse;
import com.citypass.movilidad.model.CityIncident;
import com.citypass.movilidad.repository.CityIncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

/**
 * Saca de las candidatas las estaciones que quedan cerca de un incidente activo
 * (un corte de tránsito, un incendio). CU-INT-01, paso 8.
 *
 * Si todas las candidatas están afectadas, devuelve una lista vacía: no se recomienda
 * una estación insegura incluso cuando no haya alternativas.
 */
@Component
public class IncidentProximityFilter {

    private static final Logger LOG = LoggerFactory.getLogger(IncidentProximityFilter.class);

    private final CityIncidentRepository incidentRepository;
    private final int incidentRadiusMeters;

    public IncidentProximityFilter(CityIncidentRepository incidentRepository,
                                   @Value("${movilidad.incidents.radius-meters:300}") int incidentRadiusMeters) {
        this.incidentRepository = incidentRepository;
        this.incidentRadiusMeters = incidentRadiusMeters;
    }

    public List<NearbyStationResponse> apply(List<NearbyStationResponse> candidates) {
        return filter(candidates).safe();
    }

    /** Resultado útil para informar a Espacios cuántas estaciones se descartaron. */
    public FilterResult filter(List<NearbyStationResponse> candidates) {
        if (candidates.isEmpty()) return new FilterResult(candidates, 0);
        List<CityIncident> incidents = incidentRepository.findByActiveTrue();
        if (incidents.isEmpty()) return new FilterResult(candidates, 0);

        List<NearbyStationResponse> clear = candidates.stream()
                .filter(station -> incidents.stream().noneMatch(incident -> isNear(station, incident)))
                .toList();

        if (clear.isEmpty()) {
            LOG.warn("No hay estaciones seguras: todas las candidatas están próximas a incidentes activos");
            return new FilterResult(List.of(), candidates.size());
        }

        if (clear.size() < candidates.size()) {
            LOG.info("Se descartaron {} estaciones cercanas a incidentes activos", candidates.size() - clear.size());
        }
        return new FilterResult(clear, candidates.size() - clear.size());
    }

    public record FilterResult(List<NearbyStationResponse> safe, int discarded) { }

    private boolean isNear(NearbyStationResponse station, CityIncident incident) {
        return GeoDistance.meters(
                station.latitude().doubleValue(), station.longitude().doubleValue(),
                incident.getLatitude().doubleValue(), incident.getLongitude().doubleValue())
                <= incidentRadiusMeters;
    }
}
