package com.citypass.movilidad.service;

import com.citypass.movilidad.config.NearbyStationProperties;
import com.citypass.movilidad.dto.NearbyStationResponse;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.projection.NearbyStationProjection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Búsqueda de estaciones activas cercanas a una ubicación, con su disponibilidad. */
@Service
@Transactional(readOnly = true)
public class NearbyStationService {

    private final StationRepository stationRepository;
    private final StationAvailabilityService stationAvailabilityService;
    private final NearbyStationProperties properties;

    public NearbyStationService(StationRepository stationRepository,
                                StationAvailabilityService stationAvailabilityService,
                                NearbyStationProperties properties) {
        this.stationRepository = stationRepository;
        this.stationAvailabilityService = stationAvailabilityService;
        this.properties = properties;
    }

    /** Estaciones dentro del radio, de la más cercana a la más lejana (radio y límite opcionales). */
    public List<NearbyStationResponse> findNearby(double latitude, double longitude,
                                                  Integer radiusMeters, Integer limit) {
        // Radio y límite se recortan al máximo configurado en lugar de dar error.
        int radius = clamp(radiusMeters, properties.defaultRadiusMeters(), properties.maxRadiusMeters());
        int maxResults = clamp(limit, properties.defaultLimit(), properties.maxLimit());

        GeoBoundingBox box = GeoBoundingBox.around(latitude, longitude, radius);
        List<NearbyStationProjection> nearby = stationRepository.findNearby(
                latitude, longitude,
                box.minLatitude(), box.maxLatitude(), box.minLongitude(), box.maxLongitude(),
                radius, StationStatus.ACTIVE.name(), maxResults);

        if (nearby.isEmpty()) {
            return List.of();
        }

        Map<Long, Integer> capacityByStationId = nearby.stream()
                .collect(Collectors.toMap(NearbyStationProjection::getId, NearbyStationProjection::getCapacity));
        Map<Long, StationAvailability> availability =
                stationAvailabilityService.availabilityFor(capacityByStationId);

        // Se conserva el orden por distancia que devolvió la base.
        return nearby.stream()
                .map(station -> toResponse(station, availability.get(station.getId())))
                .toList();
    }

    private NearbyStationResponse toResponse(NearbyStationProjection station, StationAvailability availability) {
        return new NearbyStationResponse(
                station.getId(),
                station.getName(),
                station.getAddress(),
                station.getLatitude(),
                station.getLongitude(),
                (int) Math.round(station.getDistanceMeters()),
                station.getCapacity(),
                availability.availableBikes(),
                availability.availableSlots());
    }

    private int clamp(Integer requested, int fallback, int max) {
        int value = requested == null ? fallback : requested;
        return Math.clamp(value, 1, max);
    }
}
