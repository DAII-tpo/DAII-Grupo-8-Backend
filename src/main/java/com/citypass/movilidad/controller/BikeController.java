package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.BikeCreateRequest;
import com.citypass.movilidad.dto.BikeResponse;
import com.citypass.movilidad.dto.BikeStatusChangeRequest;
import com.citypass.movilidad.dto.BikeStatusHistoryResponse;
import com.citypass.movilidad.dto.BikeTransferRequest;
import com.citypass.movilidad.exception.ErrorResponse;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.service.BikeService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/bikes")
@Tag(name = "Bicicletas", description = "Consulta y administración del parque de bicicletas")
public class BikeController {

    static final String USER_HEADER = "X-User-Id";
    private static final String USER_HEADER_DESCRIPTION =
            "ID del usuario actual. Mecanismo temporal hasta integrar el login federado de Grupo 2";

    private final BikeService bikeService;

    public BikeController(BikeService bikeService) {
        this.bikeService = bikeService;
    }

    @PostMapping
    @Operation(summary = "Dar de alta una bicicleta",
            description = "Rol funcional esperado: ADMIN. Autenticación gestionada por Login Federado (Grupo 2).")
    @ApiResponse(responseCode = "201", description = "Bicicleta creada",
            content = @Content(schema = @Schema(implementation = BikeResponse.class)))
    @ApiResponse(responseCode = "400", description = "Cuerpo ausente, ilegible o inválido",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "El usuario no es ADMIN",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "La estación indicada no existe",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Código duplicado o regla de alta incumplida",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<BikeResponse> create(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(USER_HEADER) @EntityId Long adminId,
            @Valid @RequestBody BikeCreateRequest request) {
        BikeResponse created = bikeService.create(adminId, request);
        return ResponseEntity.created(URI.create("/api/v1/bikes/" + created.id())).body(created);
    }

    @GetMapping
    @Operation(summary = "Listar bicicletas",
            description = "Devuelve todas las bicicletas que no fueron dadas de baja, ordenadas por código, "
                    + "en una sola respuesta. Pensado para el panel de administración, que antes necesitaba "
                    + "una consulta por estación. Puede filtrarse por estado.")
    @ApiResponse(responseCode = "200", description = "Bicicletas encontradas (puede venir vacía)",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = BikeResponse.class))))
    @ApiResponse(responseCode = "400", description = "El estado indicado no es válido",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "El usuario no es ADMIN",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public List<BikeResponse> findAll(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(USER_HEADER) @EntityId Long adminId,
            @Parameter(description = "Estado opcional para filtrar", example = "AVAILABLE")
            @RequestParam(required = false) BikeStatus status) {
        return bikeService.findAll(adminId, status);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar una bicicleta por ID",
            description = "Rol funcional esperado: ADMIN. Autenticación gestionada por Login Federado (Grupo 2).")
    @ApiResponse(responseCode = "200", description = "Bicicleta encontrada",
            content = @Content(schema = @Schema(implementation = BikeResponse.class)))
    @ApiResponse(responseCode = "403", description = "El usuario no es ADMIN",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "La bicicleta no existe o fue dada de baja",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public BikeResponse findById(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(USER_HEADER) @EntityId Long adminId,
            @Parameter(description = "ID de la bicicleta", example = "1") @PathVariable @EntityId Long id) {
        return bikeService.findById(adminId, id);
    }

    @GetMapping("/station/{stationId}")
    @Operation(summary = "Consultar bicicletas de una estación",
            description = "Rol funcional esperado: ADMIN. Autenticación gestionada por Login Federado (Grupo 2).")
    @ApiResponse(responseCode = "200", description = "Bicicletas activas de la estación",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = BikeResponse.class))))
    @ApiResponse(responseCode = "403", description = "El usuario no es ADMIN",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "La estación no existe o fue dada de baja",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public List<BikeResponse> findByStation(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(USER_HEADER) @EntityId Long adminId,
            @Parameter(description = "ID de la estación", example = "1") @PathVariable @EntityId Long stationId) {
        return bikeService.findByStation(adminId, stationId);
    }

    @GetMapping("/available")
    @Operation(summary = "Consultar bicicletas disponibles",
            description = "Roles funcionales esperados: USER y ADMIN. Autenticación gestionada por "
                    + "Login Federado (Grupo 2). "
                    + "Puede filtrarse por estación.")
    @ApiResponse(responseCode = "200", description = "Bicicletas disponibles",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = BikeResponse.class))))
    @ApiResponse(responseCode = "400", description = "El ID de estación no tiene un formato válido",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "La estación no existe o fue dada de baja",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "La estación no está activa",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public List<BikeResponse> findAvailable(
            @Parameter(description = "ID opcional de la estación", example = "1")
            @RequestParam(required = false) @EntityId Long stationId) {
        return bikeService.findAvailable(stationId);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Cambiar el estado de una bicicleta",
            description = "Rol funcional esperado: ADMIN. Autenticación gestionada por Login Federado (Grupo 2).")
    @ApiResponse(responseCode = "200", description = "Estado actualizado",
            content = @Content(schema = @Schema(implementation = BikeResponse.class)))
    @ApiResponse(responseCode = "400", description = "Cuerpo ausente, ilegible o inválido",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "El usuario no es ADMIN",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "La bicicleta no existe o fue dada de baja",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Transición administrativa no permitida",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public BikeResponse changeStatus(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(USER_HEADER) @EntityId Long adminId,
            @Parameter(description = "ID de la bicicleta", example = "1") @PathVariable @EntityId Long id,
            @Valid @RequestBody BikeStatusChangeRequest request) {
        return bikeService.changeStatus(adminId, id, request);
    }

    @PatchMapping("/{id}/station")
    @Operation(summary = "Trasladar una bicicleta",
            description = "Rol funcional esperado: ADMIN. Autenticación gestionada por Login Federado (Grupo 2).")
    @ApiResponse(responseCode = "200", description = "Bicicleta trasladada",
            content = @Content(schema = @Schema(implementation = BikeResponse.class)))
    @ApiResponse(responseCode = "400", description = "Cuerpo ausente, ilegible o inválido",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "El usuario no es ADMIN",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "La bicicleta o la estación no existen",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Traslado no permitido o estación sin capacidad",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public BikeResponse transfer(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(USER_HEADER) @EntityId Long adminId,
            @Parameter(description = "ID de la bicicleta", example = "1") @PathVariable @EntityId Long id,
            @Valid @RequestBody BikeTransferRequest request) {
        return bikeService.transfer(adminId, id, request.stationId());
    }

    @GetMapping("/{id}/status-history")
    @Operation(summary = "Consultar el historial de estados",
            description = "Rol funcional esperado: ADMIN. Autenticación gestionada por Login Federado (Grupo 2).")
    @ApiResponse(responseCode = "200", description = "Historial en orden descendente por fecha",
            content = @Content(array = @ArraySchema(
            schema = @Schema(implementation = BikeStatusHistoryResponse.class))))
    @ApiResponse(responseCode = "403", description = "El usuario no es ADMIN",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "La bicicleta no existe",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public List<BikeStatusHistoryResponse> history(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(USER_HEADER) @EntityId Long adminId,
            @Parameter(description = "ID de la bicicleta", example = "1") @PathVariable @EntityId Long id) {
        return bikeService.history(adminId, id);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Dar de baja lógica una bicicleta",
            description = "Rol funcional esperado: ADMIN. Autenticación gestionada por Login Federado (Grupo 2).")
    @ApiResponse(responseCode = "204", description = "Bicicleta dada de baja")
    @ApiResponse(responseCode = "403", description = "El usuario no es ADMIN",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "La bicicleta no existe o ya fue dada de baja",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Una bicicleta IN_USE no puede darse de baja",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Void> delete(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(USER_HEADER) @EntityId Long adminId,
            @Parameter(description = "ID de la bicicleta", example = "1") @PathVariable @EntityId Long id) {
        bikeService.delete(adminId, id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
