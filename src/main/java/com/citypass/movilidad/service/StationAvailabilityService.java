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

    private List<StationAvailabilityResponse> buildAvailability(List<Station> stations) {
        if (stations.isEmpty()) {
            return List.of();
        }
        List<Long> stationIds = stations.stream().map(Station::getId).toList();
        Map<Long, StationBikeCountProjection> countsByStation =
                bikeRepository.countBikesByStationIds(stationIds, BikeStatus.AVAILABLE).stream()
                        .collect(Collectors.toMap(StationBikeCountProjection::getStationId, Function.identity()));

        Instant checkedAt = Instant.now();
        return stations.stream()
                .map(station -> toResponse(station, countsByStation.get(station.getId()), checkedAt))
                .toList();
    }

    private StationAvailabilityResponse toResponse(Station station, StationBikeCountProjection counts,
                                                   Instant checkedAt) {
        long occupiedDocks = counts == null ? 0L : counts.getTotalBikes();
        long availableBikes = counts == null ? 0L : counts.getAvailableBikes();
        int capacity = station.getCapacity();
        // Nunca negativo: el dataset importado puede traer más bicicletas que anclajes declarados.
        int availableSlots = (int) Math.max(0L, capacity - occupiedDocks);

        return new StationAvailabilityResponse(
                station.getId(),
                station.getName(),
                station.getStatus(),
                capacity,
                (int) availableBikes,
                availableSlots,
                checkedAt);
    }
}
