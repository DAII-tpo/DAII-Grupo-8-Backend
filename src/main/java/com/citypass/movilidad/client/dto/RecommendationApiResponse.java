package com.citypass.movilidad.client.dto;

import com.citypass.movilidad.model.enums.ExplanationCode;
import com.citypass.movilidad.model.enums.NoRecommendationReason;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import com.citypass.movilidad.model.enums.RecommendationStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Respuesta de POST /v1/recommendations (MOV-041).
 *
 * No se reexpone tal cual al frontend: el backend la traduce a StationRecommendationResponse
 * agregándole los datos de estación que el servicio de recomendación no conoce (nombre,
 * dirección) y el mensaje para el usuario.
 *
 * Se ignoran las propiedades desconocidas para que un campo nuevo del servicio no rompa la
 * integración; el `ranking` completo se descarta porque el frontend no lo necesita.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecommendationApiResponse(RecommendationStatus status, NoRecommendationReason reason,
                                        RecommendationPurpose purpose, String modelVersion,
                                        ScoredStation recommendation, List<ScoredStation> alternatives,
                                        Explanation explanation) {

    /** Estación candidata con el score que le dio el modelo (0-1). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScoredStation(long stationId, double score, int distanceMeters,
                                int availableBikes, int availableDocks) {
    }

    /** Por qué el modelo eligió esa estación. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Explanation(ExplanationCode code, StationRef comparedTo, List<Factor> factors) {
    }

    /** Estación más cercana, cuando no es la recomendada. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StationRef(long stationId, int distanceMeters, int availableBikes, int availableDocks) {
    }

    /** Aporte de una feature a la decisión del modelo; positivo favorece a la recomendada. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Factor(String feature, double impact) {
    }
}
