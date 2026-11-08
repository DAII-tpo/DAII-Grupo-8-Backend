package com.citypass.movilidad.controller;
import com.citypass.movilidad.config.CurrentUserId;
import com.citypass.movilidad.dto.*;
import com.citypass.movilidad.exception.ErrorResponse;
import com.citypass.movilidad.service.MaintenanceService;
import com.citypass.movilidad.validation.EntityId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/** Endpoints de mantenimiento de bicis (solo admin). */
@RestController
@RequestMapping("/api/v1/maintenance")
@Tag(name="Mantenimiento", description="Gestión administrativa del mantenimiento de bicicletas")
// El 401 vale para todos los endpoints de la clase: springdoc propaga un @ApiResponse
// declarado a nivel de clase a cada operación.
@ApiResponse(responseCode = "401", description = "Token ausente o inválido",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
@ApiResponse(responseCode = "403", description = "El usuario no pertenece al grupo LDAP de administradores",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
public class MaintenanceController {
    private final MaintenanceService service;
    public MaintenanceController(MaintenanceService service){this.service=service;}
    /** GET /maintenance: lista las órdenes de mantenimiento. */
    @GetMapping @Operation(summary="Listar mantenimientos")
    public List<MaintenanceResponse> findAll(@CurrentUserId Long adminId){return service.findAll(adminId);}
    /** POST /maintenance: abre una orden y manda la bici a mantenimiento. */
    @PostMapping @Operation(summary="Enviar una bicicleta a mantenimiento")
    public ResponseEntity<MaintenanceResponse> create(@CurrentUserId Long adminId,
            @Valid @RequestBody MaintenanceCreateRequest request){
        var created=service.create(adminId,request);
        return ResponseEntity.created(URI.create("/api/v1/maintenance/"+created.id())).body(created);
    }
    /** PATCH /maintenance/{id}/complete: cierra la orden y devuelve la bici a una estación. */
    @PatchMapping("/{id}/complete") @Operation(summary="Finalizar un mantenimiento")
    public MaintenanceResponse complete(@CurrentUserId Long adminId,
            @PathVariable @EntityId Long id,@Valid @RequestBody MaintenanceCompleteRequest request){
        return service.complete(adminId,id,request);
    }
}
