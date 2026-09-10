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

/**
 * Búsqueda de estaciones cercanas a una ubicación (MOV-017).
 *
 * El orden y el filtro por distancia los resuelve MySQL; la disponibilidad de cada resultado
 * se delega en StationAvailabilityService, que es el único lugar donde vive esa regla.
 */
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

    /**
     * @param radiusMeters radio de búsqueda; si es null se usa el default configurado
     * @param limit        máximo de resultados; si es null se usa el default configurado
     */
    public List<NearbyStationResponse> findNearby(double latitude, double longitude,
                                                  Integer radiusMeters, Integer limit) {
        // Se recortan contra el tope configurado en vez de rechazarse: un cliente no puede
        // pedir la ciudad entera, pero tampoco recibe un error por pasarse de largo.
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

        // Se recorre la lista original para conservar el orden por distancia que dio la base.
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
