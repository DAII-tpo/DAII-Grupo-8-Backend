package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.enums.ExplanationCode;
import com.citypass.movilidad.model.enums.NoRecommendationReason;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import com.citypass.movilidad.model.enums.RecommendationSource;
import com.citypass.movilidad.model.enums.RecommendationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "Recomendación de estación para retirar o devolver una bicicleta. "
        + "Siempre responde 200: cuando no hay nada para recomendar, el detalle viaja en "
        + "'status' y 'reason'.")
public record StationRecommendationResponse(

        @Schema(description = "RECOMMENDED si hay una estación sugerida; NO_RECOMMENDATION si no la hay",
                example = "RECOMMENDED")
        RecommendationStatus status,

        @Schema(description = "Quién decidió: MODEL (el modelo de recomendación) o FALLBACK "
                + "(el backend, porque el modelo no estaba disponible o no hizo falta consultarlo)",
                example = "MODEL")
        RecommendationSource source,

        @Schema(description = "Propósito consultado", example = "PICKUP")
        RecommendationPurpose purpose,

        @Schema(description = "Motivo, solo cuando status es NO_RECOMMENDATION", nullable = true,
                example = "NO_VIABLE_STATION")
        NoRecommendationReason reason,

        @Schema(description = "Texto listo para mostrar al usuario, en español",
                example = "Te recomendamos CERRITO (236 m) en lugar de DIAGONAL NORTE (193 m): "
                        + "en DIAGONAL NORTE queda solo 1 bicicleta y podría no estar cuando llegues; "
                        + "en CERRITO hay 13.")
        String message,

        @Schema(description = "Estación recomendada; null cuando status es NO_RECOMMENDATION", nullable = true)
        RecommendedStation station,

        @Schema(description = "Hasta 2 alternativas viables, en orden de conveniencia")
        List<RecommendedStation> alternatives,

        @Schema(description = "Justificación del modelo; null cuando decidió el respaldo o no hay "
                + "recomendación", nullable = true)
        Explanation explanation,

        @Schema(description = "Versión del modelo que decidió; null cuando decidió el respaldo",
                nullable = true, example = "logreg-synthetic-v1")
        String modelVersion,

        @Schema(description = "Momento en que se generó la recomendación. La disponibilidad cambia: "
                + "pasado un rato conviene volver a consultar", example = "2025-09-20T14:31:05Z")
        Instant generatedAt) {

    @Schema(description = "Estación sugerida, con su distancia y disponibilidad al momento de la consulta")
    public record RecommendedStation(

            @Schema(description = "ID de la estación", example = "77")
            Long stationId,

            @Schema(description = "Nombre de la estación", example = "CERRITO")
            String stationName,

            @Schema(description = "Dirección de la estación", example = "Cerrito 1300")
            String address,

            @Schema(description = "Latitud de la estación", example = "-34.6026")
            BigDecimal latitude,

            @Schema(description = "Longitud de la estación", example = "-58.3838")
            BigDecimal longitude,

            @Schema(description = "Distancia en metros desde la ubicación consultada", example = "236")
            Integer distanceMeters,

            @Schema(description = "Cantidad total de anclajes de la estación", example = "30")
            Integer capacity,

            @Schema(description = "Bicicletas disponibles para retirar", example = "13")
            Integer availableBikes,

            @Schema(description = "Anclajes libres para devolver", example = "17")
            Integer availableSlots,

            @Schema(description = "Probabilidad de 'buena elección' según el modelo, entre 0 y 1. "
                    + "Null cuando decidió el respaldo", nullable = true, example = "0.768498")
            Double score) {
    }

    @Schema(description = "Por qué el modelo eligió esa estación")
    public record Explanation(

            @Schema(description = "Situación de la estación más cercana en el momento de la consulta",
                    example = "NEAREST_LOW_AVAILABILITY")
            ExplanationCode code,

            @Schema(description = "Estación más cercana, cuando no es la recomendada", nullable = true)
            ComparedStation comparedTo,

            @Schema(description = "Aporte de cada señal a la decisión, de mayor a menor peso. "
                    + "Positivo favorece a la estación recomendada")
            List<Factor> factors) {
    }

    @Schema(description = "Estación más cercana, contra la que se comparó la recomendada")
    public record ComparedStation(

            @Schema(description = "ID de la estación", example = "44")
            Long stationId,

            @Schema(description = "Nombre de la estación", example = "DIAGONAL NORTE")
            String stationName,

            @Schema(description = "Distancia en metros desde la ubicación consultada", example = "193")
            Integer distanceMeters,

            @Schema(description = "Bicicletas disponibles para retirar", example = "1")
            Integer availableBikes,

            @Schema(description = "Anclajes libres para devolver", example = "17")
            Integer availableSlots) {
    }

    @Schema(description = "Aporte de una señal del modelo a la decisión")
    public record Factor(

            @Schema(description = "Señal considerada por el modelo", example = "secure_units")
            String feature,

            @Schema(description = "Aporte a la decisión; positivo favorece a la recomendada",
                    example = "2.759")
            Double impact) {
    }
}
