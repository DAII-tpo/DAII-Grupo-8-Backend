package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.EventMobilityDecisionRequest;
import com.citypass.movilidad.dto.EventMobilityDecisionResponse;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.CulturalDestination;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import com.citypass.movilidad.repository.CulturalDestinationRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

@Service
@Slf4j
public class EventMobilityDecisionService {
 private final CulturalDestinationRepository events;
 private final StationRecommendationService recommendations;
 private final EventArrivalIntentService intentions;
 @Autowired
 public EventMobilityDecisionService(CulturalDestinationRepository events,
                                     StationRecommendationService recommendations,
                                     EventArrivalIntentService intentions) {
  this.events=events; this.recommendations=recommendations; this.intentions=intentions;
 }
 public EventMobilityDecisionService(CulturalDestinationRepository events,
                                     StationRecommendationService recommendations) {
  this(events, recommendations, null);
 }
 public EventMobilityDecisionResponse decide(String eventId, EventMobilityDecisionRequest req) {
  CulturalDestination event=events.findById(eventId).filter(CulturalDestination::isRoutable)
   .filter(destination -> destination.getEndsAt() != null && destination.getEndsAt().isAfter(Instant.now()))
   .orElseThrow(() -> new ResourceNotFoundException("Evento no disponible: "+eventId));
  return req.attending()?attending(event,req):avoiding(event,req);
 }
 private EventMobilityDecisionResponse attending(CulturalDestination event,EventMobilityDecisionRequest req) {
  if (req.originLatitude() == null || req.originLongitude() == null) {
   throw new IllegalArgumentException("La ubicación de origen es obligatoria para planificar la llegada");
  }
  var pickup=recommendations.recommend(req.originLatitude(),req.originLongitude(),RecommendationPurpose.PICKUP);
  EventRecommendationResult projected = projected(event, false);
  var dropoff = projected == null
          ? recommendations.recommendDestination(event.getLatitude().doubleValue(), event.getLongitude().doubleValue(), event.expectedDemand())
          : projected.recommendation();
  return new EventMobilityDecisionResponse("ATTENDING",pickup,dropoff,null,
          "La disponibilidad es informativa y puede cambiar; no se reservó ninguna bicicleta.",
          projected == null ? List.of() : projected.projections());
 }
 private EventMobilityDecisionResponse avoiding(CulturalDestination event,EventMobilityDecisionRequest req){
  EventRecommendationResult projected = projected(event, true);
  var alternative = projected == null
          ? recommendations.recommendAlternativeDropoff(event.getLatitude().doubleValue(), event.getLongitude().doubleValue(), event.expectedDemand())
          : projected.recommendation();
  return new EventMobilityDecisionResponse("AVOIDING_EVENT",null,null,alternative,
          "Estación real sugerida fuera del entorno inmediato del evento. No se afirma que exista congestión real.",
          projected == null ? List.of() : projected.projections());
 }

 private EventRecommendationResult projected(CulturalDestination event, boolean outsideEventZone) {
  if (intentions == null) return null;
  try {
   Map<Long, Integer> arrivals = intentions.projectedArrivals(event.getEventId(),
           event.getStartsAt().minusSeconds(7200), event.getEndsAt());
   if (arrivals.isEmpty()) return null;
   return recommendations.recommendEventDestination(event.getLatitude().doubleValue(),
           event.getLongitude().doubleValue(), arrivals, outsideEventZone);
  } catch (RuntimeException exception) {
   log.warn("No se pudo consultar demanda proyectada del evento {}. Se usa la recomendación actual.",
           event.getEventId(), exception);
   return null;
  }
 }
}
