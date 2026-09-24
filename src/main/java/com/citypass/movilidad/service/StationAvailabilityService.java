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

/** Disponibilidad de estaciones, calculada al momento de la consulta (no se guardan contadores). */
@Service
@Transactional(readOnly = true)
public class StationAvailabilityService {

    private final StationRepository stationRepository;
    private final BikeRepository bikeRepository;

    public StationAvailabilityService(StationRepository stationRepository, BikeRepository bikeRepository) {
        this.stationRepository = stationRepository;
        this.bikeRepository = bikeRepository;
    }

    /** Disponibilidad de una estación, en cualquier estado (el estado viaja en la respuesta). */
    public StationAvailabilityResponse getByStationId(Long stationId) {
        Station station = stationRepository.findByIdAndDeletedAtIsNull(stationId)
                .orElseThrow(() -> new ResourceNotFoundException("Estación no encontrada: " + stationId));
        return buildAvailability(List.of(station)).getFirst();
    }

    /** Disponibilidad de todas las estaciones vigentes, con una sola consulta de conteo. */
    public List<StationAvailabilityResponse> getAll() {
        return buildAvailability(stationRepository.findAllByDeletedAtIsNullOrderByName());
    }

    /** Disponibilidad de un grupo de estaciones (id -> capacidad) con una única consulta agrupada. */
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
            // Sin fila de conteo = estación sin bicis.
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
