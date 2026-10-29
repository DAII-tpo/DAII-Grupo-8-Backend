package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.response.CicloviaObstructionResponse;
import com.citypass.movilidad.service.CicloviaObstructionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/movilidad/obstrucciones")
@Tag(name = "Obstrucciones de Ciclovía", description = "Consulta de ciclovías bloqueadas e incidentes de tránsito que afectan la movilidad")
public class CicloviaObstructionController {

    private final CicloviaObstructionService obstructionService;

    public CicloviaObstructionController(CicloviaObstructionService obstructionService) {
        this.obstructionService = obstructionService;
    }

    @GetMapping("/activas")
    @Operation(summary = "Obtener todas las obstrucciones viales y de ciclovía activas con desvíos sugeridos")
    public ResponseEntity<List<CicloviaObstructionResponse>> getActiveObstructions() {
        return ResponseEntity.ok(obstructionService.obtenerObstruccionesActivas());
    }
}
