package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.NearbyStationResponse;
import com.citypass.movilidad.service.NearbyStationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stations")
@Validated
@Tag(name = "Mapas y rutas", description = "Búsqueda de estaciones por cercanía a una ubicación")
public class NearbyStationController {

    private final NearbyStationService nearbyStationService;

    public NearbyStationController(NearbyStationService nearbyStationService) {
        this.nearbyStationService = nearbyStationService;
    }

    @GetMapping("/nearby")
    @Operation(summary = "Estaciones cercanas a una ubicación",
            description = "Devuelve las estaciones activas dentro del radio indicado, ordenadas de más "
                    + "cercana a más lejana, cada una con su distancia y su disponibilidad actual. "
                    + "Disponible sin autenticación para usuarios de la aplicación.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estaciones ordenadas por distancia"),
            @ApiResponse(responseCode = "400", description = "Coordenadas ausentes o fuera de rango")
    })
    public List<NearbyStationResponse> findNearby(

            @Parameter(description = "Latitud de la ubicación del usuario", example = "-34.6037", required = true)
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,

            @Parameter(description = "Longitud de la ubicación del usuario", example = "-58.3816", required = true)
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lng,

            @Parameter(description = "Radio de búsqueda en metros. Si se omite se usa el radio operativo "
                    + "configurado; los valores por encima del tope se recortan.", example = "500")
            @RequestParam(required = false) @Positive Integer radius,

            @Parameter(description = "Máximo de estaciones a devolver. Si se omite se usa el default "
                    + "configurado; los valores por encima del tope se recortan.", example = "10")
            @RequestParam(required = false) @Positive Integer limit) {

        return nearbyStationService.findNearby(lat, lng, radius, limit);
    }
}
