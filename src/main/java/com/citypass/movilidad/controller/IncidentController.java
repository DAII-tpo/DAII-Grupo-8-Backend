package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.IncidentCreateRequest;
import com.citypass.movilidad.dto.IncidentResponse;
import com.citypass.movilidad.dto.IncidentTypeResponse;
import com.citypass.movilidad.exception.ErrorResponse;
import com.citypass.movilidad.service.IncidentService;
import com.citypass.movilidad.validation.EntityId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/incidents")
@Tag(name = "Incidencias", description = "Consulta de tipos y reporte de incidencias de bicicletas")
public class IncidentController {

    static final String USER_HEADER = "X-User-Id";
    private static final String USER_HEADER_DESCRIPTION =
            "ID del usuario actual. Mecanismo temporal hasta integrar el login federado de Grupo 2";

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @GetMapping("/types")
    @Operation(summary = "Consultar tipos de incidencia activos")
    @ApiResponse(responseCode = "200", description = "Tipos disponibles",
            content = @Content(array = @ArraySchema(
                    schema = @Schema(implementation = IncidentTypeResponse.class))))
    public List<IncidentTypeResponse> findTypes() {
        return incidentService.findActiveTypes();
    }

    @PostMapping
    @Operation(summary = "Reportar una incidencia",
            description = "Registra una incidencia OPEN asociada al usuario y a la bicicleta")
    @ApiResponse(responseCode = "201", description = "Incidencia registrada",
            content = @Content(schema = @Schema(implementation = IncidentResponse.class)))
    @ApiResponse(responseCode = "400", description = "Header o cuerpo inválido",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Usuario, bicicleta o tipo inexistente/inactivo",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Usuario no habilitado",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<IncidentResponse> report(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(USER_HEADER) @EntityId Long userId,
            @Valid @RequestBody IncidentCreateRequest request) {
        IncidentResponse created = incidentService.report(userId, request);
        return ResponseEntity.created(URI.create("/api/v1/incidents/" + created.id())).body(created);
    }
}
