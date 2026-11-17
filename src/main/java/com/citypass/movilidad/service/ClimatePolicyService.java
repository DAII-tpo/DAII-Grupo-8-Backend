package com.citypass.movilidad.service;

import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.dto.ClimateAlertResponse;
import com.citypass.movilidad.dto.StationClimateStatusResponse;
import com.citypass.movilidad.repository.ClimateAlertRepository;
import com.citypass.movilidad.repository.StationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.function.BinaryOperator;

/** Consulta la alerta activa más restrictiva (según la matriz fenómeno × criticidad) para una estación. */
@Service
public class ClimatePolicyService {
    /**
     * Más restrictiva gana: mayor respuesta, después mayor criticidad, después la vigente desde antes y por
     * último el id menor, para que el resultado no dependa del orden en que llegan las alertas.
     */
    static final Comparator<EffectiveClimate> MOST_RESTRICTIVE = Comparator
            .comparing(EffectiveClimate::response)
            .thenComparing(effective -> effective.alert().getSeverity())
            .thenComparing(effective -> effective.alert().getValidFrom(),
                    Comparator.nullsFirst(Comparator.<Instant>reverseOrder()))
            .thenComparing(effective -> effective.alert().getId(),
                    Comparator.nullsFirst(Comparator.<Long>reverseOrder()));
    private static final BinaryOperator<EffectiveClimate> STRONGEST =
            (first, second) -> MOST_RESTRICTIVE.compare(first, second) >= 0 ? first : second;

    private final ClimateAlertRepository alerts;
    private final StationRepository stations;
    private final ClimatePolicyMatrix matrix;

    public ClimatePolicyService(ClimateAlertRepository alerts, StationRepository stations, ClimatePolicyMatrix matrix) {
        this.alerts = alerts;
        this.stations = stations;
        this.matrix = matrix;
    }

    /** Alerta ACTIVA del barrio de la estación con la respuesta que corresponde aplicar. */
    public record EffectiveClimate(ClimateAlert alert, ClimateResponse response) {
        public ClimateAlertResponse toResponse() {
            return ClimateAlertResponse.from(alert, response);
        }
    }

    public Optional<EffectiveClimate> effective(Station station) {
        if (station == null || station.getNeighborhood() == null) {
            return Optional.empty();
        }
        return alerts.findByStatusAndNeighborhoodId(ClimateAlert.Status.ACTIVA,
                        station.getNeighborhood().getId()).stream()
                .map(this::effectiveOf)
                .max(MOST_RESTRICTIVE);
    }

    public EffectiveClimate effectiveOf(ClimateAlert alert) {
        return new EffectiveClimate(alert, matrix.responseFor(alert));
    }

    @Transactional(readOnly = true)
    public StationClimateStatusResponse stationStatus(Station station) {
        return new StationClimateStatusResponse(station.getId(),
                effective(station).map(EffectiveClimate::toResponse).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<StationClimateStatusResponse> affectedStationStatuses() {
        Map<String, EffectiveClimate> strongest = new HashMap<>();
        for (ClimateAlert alert : alerts.findByStatus(ClimateAlert.Status.ACTIVA)) {
            EffectiveClimate effective = effectiveOf(alert);
            alert.getNeighborhoods().forEach(neighborhood ->
                    strongest.merge(neighborhood.getId(), effective, STRONGEST));
        }
        if (strongest.isEmpty()) {
            return List.of();
        }
        Set<String> ids = strongest.keySet();
        return stations.findByNeighborhood_IdInAndDeletedAtIsNull(ids).stream()
                .map(station -> new StationClimateStatusResponse(station.getId(),
                        strongest.get(station.getNeighborhood().getId()).toResponse()))
                .toList();
    }
}
