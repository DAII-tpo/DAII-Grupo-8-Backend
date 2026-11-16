package com.citypass.movilidad.service;

import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.dto.ClimateAlertResponse;
import com.citypass.movilidad.dto.StationClimateStatusResponse;
import com.citypass.movilidad.repository.ClimateAlertRepository;
import com.citypass.movilidad.repository.StationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

/** Consulta la alerta activa de mayor criticidad para una estación. */
@Service
public class ClimatePolicyService {
    private final ClimateAlertRepository alerts;
    private final StationRepository stations;

    public ClimatePolicyService(ClimateAlertRepository alerts, StationRepository stations) {
        this.alerts = alerts;
        this.stations = stations;
    }

    public Optional<ClimateAlert> effectiveAlert(Station station) {
        if (station == null || station.getNeighborhood() == null) {
            return Optional.empty();
        }
        return alerts.findByStatusAndNeighborhoodId(ClimateAlert.Status.ACTIVA,
                        station.getNeighborhood().getId()).stream()
                .max(Comparator.comparing(ClimateAlert::getSeverity));
    }

    @Transactional(readOnly = true)
    public StationClimateStatusResponse stationStatus(Station station) {
        return new StationClimateStatusResponse(station.getId(),
                effectiveAlert(station).map(ClimateAlertResponse::from).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<StationClimateStatusResponse> affectedStationStatuses() {
        Map<String, ClimateAlert> strongest = new HashMap<>();
        for (ClimateAlert alert : alerts.findByStatus(ClimateAlert.Status.ACTIVA)) {
            alert.getNeighborhoods().forEach(neighborhood -> strongest.merge(neighborhood.getId(), alert,
                    (first, second) -> first.getSeverity().ordinal() >= second.getSeverity().ordinal()
                            ? first : second));
        }
        if (strongest.isEmpty()) {
            return List.of();
        }
        Set<String> ids = strongest.keySet();
        return stations.findByNeighborhood_IdInAndDeletedAtIsNull(ids).stream()
                .map(station -> new StationClimateStatusResponse(station.getId(),
                        ClimateAlertResponse.from(strongest.get(station.getNeighborhood().getId()))))
                .toList();
    }
}
