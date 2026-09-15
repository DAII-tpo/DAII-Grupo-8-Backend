package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.StationAvailabilityResponse;
import com.citypass.movilidad.exception.ErrorResponse;
import com.citypass.movilidad.service.StationAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stations")
@Tag(name = "Disponibilidad de estaciones",
        description = "Consulta de bicicletas disponibles y anclajes libres por estación")
public class StationAvailabilityController {

    private final StationAvailabilityService stationAvailabilityService;

    public StationAvailabilityController(StationAvailabilityService stationAvailabilityService) {
        this.stationAvailabilityService = stationAvailabilityService;
    }

    @GetMapping("/availability")
    @Operation(summary = "Disponibilidad de todas las estaciones vigentes",
            description = "Operación pública, incluso en modo seguro. Incluye estaciones activas, inactivas "
                    + "y en mantenimiento que no tengan baja lógica.")
    @ApiResponse(responseCode = "200", description = "Disponibilidad calculada para las estaciones vigentes",
            content = @Content(array = @ArraySchema(
                    schema = @Schema(implementation = StationAvailabilityResponse.class))))
    public List<StationAvailabilityResponse> getAll() {
        return stationAvailabilityService.getAll();
    }

    @GetMapping("/{stationId}/availability")
    @Operation(summary = "Disponibilidad de una estación",
            description = "Devuelve capacidad, bicicletas disponibles y anclajes libres al momento de la consulta")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Disponibilidad calculada",
                    content = @Content(schema = @Schema(implementation = StationAvailabilityResponse.class))),
            @ApiResponse(responseCode = "404", description = "La estación no existe o fue dada de baja",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public StationAvailabilityResponse getByStationId(
            @Parameter(description = "ID de la estación", example = "1") @PathVariable Long stationId) {
        return stationAvailabilityService.getByStationId(stationId);
    }
}
