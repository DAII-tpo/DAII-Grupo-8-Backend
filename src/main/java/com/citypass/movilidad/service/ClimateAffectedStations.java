package com.citypass.movilidad.service;

import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.model.Neighborhood;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.repository.StationRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Estaciones (no eliminadas) de los barrios de una alerta; el filtro por estado lo aplica cada uso. */
@Component
public class ClimateAffectedStations {
    private final StationRepository stations;

    public ClimateAffectedStations(StationRepository stations) {
        this.stations = stations;
    }

    public List<Station> of(ClimateAlert alert) {
        Set<String> ids = neighborhoodIds(alert);
        return ids.isEmpty() ? List.of() : stations.findByNeighborhood_IdInAndDeletedAtIsNull(ids);
    }

    public static Set<String> neighborhoodIds(ClimateAlert alert) {
        return alert.getNeighborhoods().stream().map(Neighborhood::getId)
                .collect(Collectors.toSet());
    }
}
