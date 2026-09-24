package com.citypass.movilidad.client.dto;

import com.citypass.movilidad.model.enums.RecommendationPurpose;

import java.util.List;

/** Request al servicio de recomendación: ubicación del usuario y estado de las candidatas. */
public record RecommendationApiRequest(RecommendationPurpose purpose, UserLocation user,
                                       List<StationSnapshot> stations) {

    /** Ubicación desde la que consulta el usuario. */
    public record UserLocation(double lat, double lng) {
    }

    /** Estado actual de una estación candidata. */
    public record StationSnapshot(long stationId, double lat, double lng, int capacity,
                                  int availableBikes, int availableDocks) {
    }
}
