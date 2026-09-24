package com.citypass.movilidad.client.dto;

import com.citypass.movilidad.model.enums.ExplanationCode;
import com.citypass.movilidad.model.enums.NoRecommendationReason;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import com.citypass.movilidad.model.enums.RecommendationStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Respuesta del servicio de recomendación. Se ignoran campos desconocidos para no romper la integración. */
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

    /** Aporte de una variable a la decisión (positivo favorece a la recomendada). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Factor(String feature, double impact) {
    }
}
