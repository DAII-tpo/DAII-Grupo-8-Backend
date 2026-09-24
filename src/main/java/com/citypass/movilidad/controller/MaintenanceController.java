package com.citypass.movilidad.controller;
import com.citypass.movilidad.dto.*;
import com.citypass.movilidad.service.MaintenanceService;
import com.citypass.movilidad.validation.EntityId;
import io.swagger.v3.oas.annotations.Operation;
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
public class MaintenanceController {
    private static final String USER_HEADER="X-User-Id";
    private final MaintenanceService service;
    public MaintenanceController(MaintenanceService service){this.service=service;}
    /** GET /maintenance: lista las órdenes de mantenimiento. */
    @GetMapping @Operation(summary="Listar mantenimientos")
    public List<MaintenanceResponse> findAll(@RequestHeader(USER_HEADER) @EntityId Long adminId){return service.findAll(adminId);}
    /** POST /maintenance: abre una orden y manda la bici a mantenimiento. */
    @PostMapping @Operation(summary="Enviar una bicicleta a mantenimiento")
    public ResponseEntity<MaintenanceResponse> create(@RequestHeader(USER_HEADER) @EntityId Long adminId,
            @Valid @RequestBody MaintenanceCreateRequest request){
        var created=service.create(adminId,request);
        return ResponseEntity.created(URI.create("/api/v1/maintenance/"+created.id())).body(created);
    }
    /** PATCH /maintenance/{id}/complete: cierra la orden y devuelve la bici a una estación. */
    @PatchMapping("/{id}/complete") @Operation(summary="Finalizar un mantenimiento")
    public MaintenanceResponse complete(@RequestHeader(USER_HEADER) @EntityId Long adminId,
            @PathVariable @EntityId Long id,@Valid @RequestBody MaintenanceCompleteRequest request){
        return service.complete(adminId,id,request);
    }
}
