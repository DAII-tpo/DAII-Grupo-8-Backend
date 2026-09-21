package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.StationAvailabilityResponse;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.repository.BikeRepository;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.projection.StationBikeCountProjection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Disponibilidad de estaciones (MOV-016).
 *
 * La disponibilidad se calcula en el momento de la consulta a partir del parque de
 * bicicletas y de la capacidad de la estación: no se persiste ningún contador, para no
 * mantener valores que puedan quedar desfasados respecto de la tabla de bicicletas.
 */
@Service
@Transactional(readOnly = true)
public class StationAvailabilityService {

    private final StationRepository stationRepository;
    private final BikeRepository bikeRepository;

    public StationAvailabilityService(StationRepository stationRepository, BikeRepository bikeRepository) {
        this.stationRepository = stationRepository;
        this.bikeRepository = bikeRepository;
    }

    /**
     * Disponibilidad de una estación puntual. Responde también para estaciones INACTIVE o en
     * MAINTENANCE: el estado viaja en la respuesta para que el cliente decida qué mostrar.
     */
    public StationAvailabilityResponse getByStationId(Long stationId) {
        Station station = stationRepository.findByIdAndDeletedAtIsNull(stationId)
                .orElseThrow(() -> new ResourceNotFoundException("Estación no encontrada: " + stationId));
        return buildAvailability(List.of(station)).getFirst();
    }

    /** Disponibilidad de todas las estaciones vigentes, resuelta con una sola consulta de conteo. */
    public List<StationAvailabilityResponse> getAll() {
        return buildAvailability(stationRepository.findAllByDeletedAtIsNullOrderByName());
    }

    /**
     * Disponibilidad de un conjunto arbitrario de estaciones, dadas sus capacidades, con una
     * única consulta agrupada. Lo consume la búsqueda de estaciones cercanas (MOV-017) para
     * no duplicar ni el conteo ni la regla de negocio.
     *
     * @param capacityByStationId capacidad declarada de cada estación, por ID
     */
    public Map<Long, StationAvailability> availabilityFor(Map<Long, Integer> capacityByStationId) {
        if (capacityByStationId.isEmpty()) {
            return Map.of();
        }
        Map<Long, StationBikeCountProjection> countsByStation =
                bikeRepository.countBikesByStationIds(capacityByStationId.keySet(), BikeStatus.AVAILABLE).stream()
                        .collect(Collectors.toMap(StationBikeCountProjection::getStationId, Function.identity()));

        Map<Long, StationAvailability> availability = new HashMap<>();
        capacityByStationId.forEach((stationId, capacity) -> {
            StationBikeCountProjection counts = countsByStation.get(stationId);
            // Sin fila de conteo significa estación sin bicicletas, no error.
            availability.put(stationId, counts == null
                    ? StationAvailability.empty(capacity)
                    : StationAvailability.of(capacity, counts.getTotalBikes(), counts.getAvailableBikes()));
        });
        return availability;
    }

    private List<StationAvailabilityResponse> buildAvailability(List<Station> stations) {
        if (stations.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> capacityByStationId = stations.stream()
                .collect(Collectors.toMap(Station::getId, Station::getCapacity));
        Map<Long, StationAvailability> availability = availabilityFor(capacityByStationId);

        Instant checkedAt = Instant.now();
        return stations.stream()
                .map(station -> toResponse(station, availability.get(station.getId()), checkedAt))
                .toList();
    }

    private StationAvailabilityResponse toResponse(Station station, StationAvailability availability,
                                                   Instant checkedAt) {
        return new StationAvailabilityResponse(
                station.getId(),
                station.getName(),
                station.getStatus(),
                station.getCapacity(),
                availability.availableBikes(),
                availability.availableSlots(),
                checkedAt);
    }
}
