package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.request.StationRequestDTO;
import com.citypass.movilidad.dto.response.StationDTO;
import com.citypass.movilidad.exception.ErrorResponse;
import com.citypass.movilidad.service.StationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stations")
@RequiredArgsConstructor
@Tag(name = "Estaciones", description = "Consulta y administración del catálogo de estaciones")
public class StationController {

    private final StationService stationService;

    @GetMapping()
    @Operation(summary = "Obtener todas las estaciones",
            description = "Roles funcionales esperados: USER y ADMIN. Autenticación gestionada por "
                    + "Login Federado (Grupo 2).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Listado de estaciones",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = StationDTO.class))))
    })
    public List<StationDTO> getStationById() {
        return stationService.getAllStations();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtener una estación por ID",
            description = "Roles funcionales esperados: USER y ADMIN. Autenticación gestionada por "
                    + "Login Federado (Grupo 2).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estación encontrada",
                    content = @Content(schema = @Schema(implementation = StationDTO.class))),
            @ApiResponse(responseCode = "404", description = "La estación no existe",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public StationDTO getStationById(
            @Parameter(description = "ID de la estación", example = "1") @PathVariable Long id) {
        return stationService.getStationById(id);
    }

    @PostMapping()
    @Operation(summary = "Crear una nueva estación",
            description = "Rol funcional esperado: ADMIN. Autenticación gestionada por Login Federado "
                    + "(Grupo 2). "
                    + "La implementación actual responde 200 OK.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estación creada",
                    content = @Content(schema = @Schema(implementation = StationDTO.class))),
            @ApiResponse(responseCode = "400", description = "Cuerpo ausente o ilegible",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public StationDTO createStation(@RequestBody StationRequestDTO stationRequestDTO) {
        return stationService.createStation(stationRequestDTO);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Actualizar una estación existente",
            description = "Rol funcional esperado: ADMIN. Autenticación gestionada por Login Federado "
                    + "(Grupo 2). "
                    + "Reemplaza los campos editables con los valores recibidos.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estación actualizada",
                    content = @Content(schema = @Schema(implementation = StationDTO.class))),
            @ApiResponse(responseCode = "400", description = "Cuerpo ausente o ilegible",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "La estación no existe",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public StationDTO updateStation(
            @Parameter(description = "ID de la estación", example = "1") @PathVariable Long id,
            @RequestBody StationRequestDTO stationRequestDTO) {
        return stationService.updateStation(id, stationRequestDTO);
    }

}
