package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.StationRecommendationResponse;
import com.citypass.movilidad.exception.ErrorResponse;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import com.citypass.movilidad.service.StationRecommendationService;
import com.citypass.movilidad.validation.Latitude;
import com.citypass.movilidad.validation.Longitude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stations")
@Tag(name = "Recomendación inteligente",
        description = "Sugerencia de la estación más conveniente según ubicación y disponibilidad")
public class StationRecommendationController {

    private final StationRecommendationService stationRecommendationService;

    public StationRecommendationController(StationRecommendationService stationRecommendationService) {
        this.stationRecommendationService = stationRecommendationService;
    }

    @GetMapping("/recommendation")
    @Operation(summary = "Estación recomendada para retirar o devolver una bicicleta",
            description = """
                    Devuelve la estación más conveniente considerando la distancia y la \
                    disponibilidad actual, por lo que puede sugerir una estación más lejana que la \
                    más cercana si a esa le queda poco stock. La decisión la toma un modelo de \
                    machine learning (MOV-041) y la respuesta incluye la justificación y un mensaje \
                    listo para mostrar.

                    Responde 200 también cuando no hay nada para recomendar: en ese caso `status` \
                    es NO_RECOMMENDATION y `reason` indica si no hay estaciones cerca o si ninguna \
                    tiene el recurso necesario. Si el modelo no está disponible, `source` pasa a \
                    FALLBACK y el backend responde con la estación más cercana que tenga el recurso, \
                    sin explicación del modelo. Disponible sin autenticación, igual que /nearby.""")
    @ApiResponse(responseCode = "200", description = "Recomendación, o el motivo por el que no la hay",
            content = @Content(schema = @Schema(implementation = StationRecommendationResponse.class)))
    @ApiResponse(responseCode = "400", description = "Parámetros ausentes, no numéricos o fuera de rango",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public StationRecommendationResponse recommend(

            @Parameter(description = "Latitud de la ubicación del usuario", example = "-34.6037", required = true)
            @RequestParam @Latitude double lat,

            @Parameter(description = "Longitud de la ubicación del usuario", example = "-58.3816", required = true)
            @RequestParam @Longitude double lng,

            @Parameter(description = "Para qué se pide la recomendación: PICKUP para retirar una "
                    + "bicicleta, DROPOFF para devolverla", example = "PICKUP")
            @RequestParam(required = false, defaultValue = "PICKUP") RecommendationPurpose purpose) {

        return stationRecommendationService.recommend(lat, lng, purpose);
    }
}
