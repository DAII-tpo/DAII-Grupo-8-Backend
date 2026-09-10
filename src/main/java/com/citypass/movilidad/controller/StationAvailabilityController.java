package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.StationAvailabilityResponse;
import com.citypass.movilidad.service.StationAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
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
            description = "Disponible sin autenticación para usuarios de la aplicación")
    public List<StationAvailabilityResponse> getAll() {
        return stationAvailabilityService.getAll();
    }

    @GetMapping("/{stationId}/availability")
    @Operation(summary = "Disponibilidad de una estación",
            description = "Devuelve capacidad, bicicletas disponibles y anclajes libres al momento de la consulta")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Disponibilidad calculada"),
            @ApiResponse(responseCode = "404", description = "La estación no existe o fue dada de baja")
    })
    public StationAvailabilityResponse getByStationId(@PathVariable Long stationId) {
        return stationAvailabilityService.getByStationId(stationId);
    }
}
