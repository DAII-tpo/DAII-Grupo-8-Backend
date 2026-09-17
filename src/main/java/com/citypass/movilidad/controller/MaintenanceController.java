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

@RestController
@RequestMapping("/api/v1/maintenance")
@Tag(name="Mantenimiento", description="Gestión administrativa del mantenimiento de bicicletas")
public class MaintenanceController {
    private static final String USER_HEADER="X-User-Id";
    private final MaintenanceService service;
    public MaintenanceController(MaintenanceService service){this.service=service;}
    @GetMapping @Operation(summary="Listar mantenimientos")
    public List<MaintenanceResponse> findAll(@RequestHeader(USER_HEADER) @EntityId Long adminId){return service.findAll(adminId);}
    @PostMapping @Operation(summary="Enviar una bicicleta a mantenimiento")
    public ResponseEntity<MaintenanceResponse> create(@RequestHeader(USER_HEADER) @EntityId Long adminId,
            @Valid @RequestBody MaintenanceCreateRequest request){
        var created=service.create(adminId,request);
        return ResponseEntity.created(URI.create("/api/v1/maintenance/"+created.id())).body(created);
    }
    @PatchMapping("/{id}/complete") @Operation(summary="Finalizar un mantenimiento")
    public MaintenanceResponse complete(@RequestHeader(USER_HEADER) @EntityId Long adminId,
            @PathVariable @EntityId Long id,@Valid @RequestBody MaintenanceCompleteRequest request){
        return service.complete(adminId,id,request);
    }
}
