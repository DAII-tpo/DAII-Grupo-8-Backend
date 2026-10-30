package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.CulturalDestinationResponse;
import com.citypass.movilidad.dto.StationRecommendationResponse;
import com.citypass.movilidad.service.CulturalDestinationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/destinations/events")
@Tag(name = "Destinos culturales", description = "Eventos de Espacios Públicos como destino de la ruta urbana")
public class CulturalDestinationController {

    private final CulturalDestinationService destinationService;

    public CulturalDestinationController(CulturalDestinationService destinationService) {
        this.destinationService = destinationService;
    }

    @GetMapping
    @Operation(summary = "Eventos culturales próximos a los que se puede llegar en bici")
    public List<CulturalDestinationResponse> upcoming() {
        return destinationService.upcoming();
    }

    @GetMapping("/{eventId}/recommendation")
    @Operation(summary = "Estación recomendada para devolver la bici al llegar al evento",
            description = "Usa la ubicación del espacio y descarta las estaciones cercanas a incidentes activos.")
    public StationRecommendationResponse recommendation(@PathVariable String eventId) {
        return destinationService.recommendDropoff(eventId);
    }
}
