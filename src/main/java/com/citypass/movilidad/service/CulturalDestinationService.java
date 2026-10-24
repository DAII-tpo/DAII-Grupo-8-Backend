package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.CulturalDestinationResponse;
import com.citypass.movilidad.dto.StationRecommendationResponse;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.CulturalDestination;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import com.citypass.movilidad.repository.CulturalDestinationRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Destinos culturales de la ruta urbana (CU-INT-01, pasos 3 y 9). */
@Service
public class CulturalDestinationService {

    private final CulturalDestinationRepository destinationRepository;
    private final StationRecommendationService recommendationService;

    public CulturalDestinationService(CulturalDestinationRepository destinationRepository,
                                      StationRecommendationService recommendationService) {
        this.destinationRepository = destinationRepository;
        this.recommendationService = recommendationService;
    }

    /** Eventos que todavía no terminaron y a los que se puede llegar en bici. */
    public List<CulturalDestinationResponse> upcoming() {
        return destinationRepository.findByEndsAtAfterOrderByStartsAtAsc(Instant.now()).stream()
                .filter(CulturalDestination::isRoutable)
                .map(CulturalDestinationResponse::from)
                .toList();
    }

    /** Dónde devolver la bici para llegar al evento, evitando incidentes activos. */
    public StationRecommendationResponse recommendDropoff(String eventId) {
        CulturalDestination destination = destinationRepository.findById(eventId)
                .filter(CulturalDestination::isRoutable)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El evento no existe, fue cancelado o su espacio no está disponible"));

        return recommendationService.recommend(destination.getLatitude().doubleValue(),
                destination.getLongitude().doubleValue(), RecommendationPurpose.DROPOFF);
    }
}
