package com.citypass.movilidad.client.dto;

import com.citypass.movilidad.model.enums.RecommendationPurpose;

import java.util.List;

/**
 * Cuerpo de POST /v1/recommendations del servicio de recomendación (MOV-041).
 *
 * El servicio no tiene base de datos: el estado de las estaciones candidatas viaja completo en
 * cada request. El backend envía únicamente estaciones habilitadas.
 */
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
