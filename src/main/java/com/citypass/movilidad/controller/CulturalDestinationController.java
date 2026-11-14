package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.CulturalDestinationResponse;
import com.citypass.movilidad.dto.EventMobilityDecisionRequest;
import com.citypass.movilidad.dto.EventMobilityDecisionResponse;
import com.citypass.movilidad.dto.EventArrivalIntentRequest;
import com.citypass.movilidad.dto.EventArrivalIntentResponse;
import com.citypass.movilidad.dto.StationRecommendationResponse;
import com.citypass.movilidad.config.CurrentUserId;
import com.citypass.movilidad.service.CulturalDestinationService;
import com.citypass.movilidad.service.EventArrivalIntentService;
import com.citypass.movilidad.service.EventMobilityDecisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/destinations/events")
@Tag(name = "Destinos culturales", description = "Eventos de Espacios Públicos como destino de la ruta urbana")
public class CulturalDestinationController {

    private final CulturalDestinationService destinationService;
    private final EventMobilityDecisionService decisionService;
    private final EventArrivalIntentService arrivalIntentService;

    public CulturalDestinationController(CulturalDestinationService destinationService,
                                         EventMobilityDecisionService decisionService,
                                         EventArrivalIntentService arrivalIntentService) {
        this.destinationService = destinationService;
        this.decisionService = decisionService;
        this.arrivalIntentService = arrivalIntentService;
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

    @PostMapping("/{eventId}/mobility-decision")
    public EventMobilityDecisionResponse decide(@PathVariable String eventId,
            @Valid @RequestBody EventMobilityDecisionRequest request) {
        return decisionService.decide(eventId, request);
    }

    @PutMapping("/{eventId}/arrival-intention")
    @Operation(summary = "Registrar o cambiar la intención de llegada del ciudadano")
    public EventArrivalIntentResponse registerArrival(@CurrentUserId Long userId,
            @PathVariable String eventId, @Valid @RequestBody EventArrivalIntentRequest request) {
        return arrivalIntentService.register(userId, eventId, request);
    }

    @GetMapping("/{eventId}/arrival-intention")
    public EventArrivalIntentResponse currentArrival(@CurrentUserId Long userId,
            @PathVariable String eventId) {
        return arrivalIntentService.current(userId, eventId).orElse(null);
    }

    @DeleteMapping("/{eventId}/arrival-intention")
    @Operation(summary = "Cancelar la intención de llegada sin afectar disponibilidad real")
    public void cancelArrival(@CurrentUserId Long userId, @PathVariable String eventId) {
        arrivalIntentService.cancel(userId, eventId);
    }
}
