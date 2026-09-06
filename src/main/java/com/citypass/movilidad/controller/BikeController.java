package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.BikeCreateRequest;
import com.citypass.movilidad.dto.BikeResponse;
import com.citypass.movilidad.dto.BikeStatusChangeRequest;
import com.citypass.movilidad.dto.BikeStatusHistoryResponse;
import com.citypass.movilidad.dto.BikeTransferRequest;
import com.citypass.movilidad.service.BikeService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/bikes")
@Tag(name = "Bicicletas", description = "Consulta y administración del parque de bicicletas")
public class BikeController {

    private final BikeService bikeService;

    public BikeController(BikeService bikeService) {
        this.bikeService = bikeService;
    }

    @PostMapping
    @Operation(summary = "Dar de alta una bicicleta", description = "Requiere rol ADMIN")
    public ResponseEntity<BikeResponse> create(@Valid @RequestBody BikeCreateRequest request) {
        BikeResponse created = bikeService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/bikes/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar una bicicleta por ID", description = "Requiere rol ADMIN")
    public BikeResponse findById(@PathVariable Long id) {
        return bikeService.findById(id);
    }

    @GetMapping("/station/{stationId}")
    @Operation(summary = "Consultar bicicletas de una estación", description = "Requiere rol ADMIN")
    public List<BikeResponse> findByStation(@PathVariable Long stationId) {
        return bikeService.findByStation(stationId);
    }

    @GetMapping("/available")
    @Operation(summary = "Consultar bicicletas disponibles",
            description = "Disponible para roles USER y ADMIN; puede filtrarse por estación")
    public List<BikeResponse> findAvailable(@RequestParam(required = false) Long stationId) {
        return bikeService.findAvailable(stationId);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Cambiar el estado de una bicicleta", description = "Requiere rol ADMIN")
    public BikeResponse changeStatus(@PathVariable Long id,
                                     @Valid @RequestBody BikeStatusChangeRequest request) {
        return bikeService.changeStatus(id, request);
    }

    @PatchMapping("/{id}/station")
    @Operation(summary = "Trasladar una bicicleta", description = "Requiere rol ADMIN")
    public BikeResponse transfer(@PathVariable Long id, @Valid @RequestBody BikeTransferRequest request) {
        return bikeService.transfer(id, request.stationId());
    }

    @GetMapping("/{id}/status-history")
    @Operation(summary = "Consultar el historial de estados", description = "Requiere rol ADMIN")
    public List<BikeStatusHistoryResponse> history(@PathVariable Long id) {
        return bikeService.history(id);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Dar de baja lógica una bicicleta", description = "Requiere rol ADMIN")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        bikeService.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
