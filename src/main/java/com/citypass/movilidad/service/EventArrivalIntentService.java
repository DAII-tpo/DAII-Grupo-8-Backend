package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.EventArrivalIntentRequest;
import com.citypass.movilidad.dto.EventArrivalIntentResponse;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.EventArrivalIntent;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.CulturalDestinationRepository;
import com.citypass.movilidad.repository.EventArrivalIntentRepository;
import com.citypass.movilidad.repository.StationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class EventArrivalIntentService {
    private final EventArrivalIntentRepository intentions;
    private final CulturalDestinationRepository events;
    private final StationRepository stations;

    public EventArrivalIntentService(EventArrivalIntentRepository intentions,
                                     CulturalDestinationRepository events,
                                     StationRepository stations) {
        this.intentions = intentions;
        this.events = events;
        this.stations = stations;
    }

    @Transactional
    public EventArrivalIntentResponse register(Long userId, String eventId, EventArrivalIntentRequest request) {
        var event = events.findById(eventId).filter(destination -> destination.isRoutable())
                .filter(destination -> destination.getEndsAt() != null && destination.getEndsAt().isAfter(Instant.now()))
                .orElseThrow(() -> new ResourceNotFoundException("Evento no disponible: " + eventId));
        var station = stations.findByIdAndDeletedAtIsNull(request.stationId())
                .filter(candidate -> candidate.getStatus() == StationStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Estación no disponible: " + request.stationId()));
        Instant firstArrival = event.getStartsAt().minus(2, ChronoUnit.HOURS);
        if (request.arrivalAt().isBefore(firstArrival) || request.arrivalAt().isAfter(event.getEndsAt())) {
            throw new IllegalArgumentException("La franja de llegada debe estar dentro de la ventana del evento");
        }

        EventArrivalIntent intent = intentions.findByUserIdAndEventId(userId, eventId)
                .orElseGet(EventArrivalIntent::new);
        intent.setUserId(userId);
        intent.setEventId(eventId);
        intent.setStationId(station.getId());
        intent.setArrivalAt(request.arrivalAt());
        intent.setStatus("ACTIVE");
        intent.setCancelledAt(null);
        return response(intentions.save(intent));
    }

    @Transactional
    public void cancel(Long userId, String eventId) {
        intentions.findByUserIdAndEventId(userId, eventId).ifPresent(intent -> {
            intent.setStatus("CANCELLED");
            intent.setCancelledAt(Instant.now());
            intentions.save(intent);
        });
    }

    @Transactional(readOnly = true)
    public Optional<EventArrivalIntentResponse> current(Long userId, String eventId) {
        return intentions.findByUserIdAndEventId(userId, eventId).map(EventArrivalIntentService::response);
    }

    @Transactional(readOnly = true)
    public Map<Long, Integer> projectedArrivals(String eventId, Instant from, Instant to) {
        return intentions.countActiveByStation(eventId, from, to).stream()
                .collect(Collectors.toMap(EventArrivalIntentRepository.ArrivalCount::getStationId,
                        count -> Math.toIntExact(count.getArrivals())));
    }

    private static EventArrivalIntentResponse response(EventArrivalIntent intent) {
        return new EventArrivalIntentResponse(intent.getEventId(), intent.getStationId(),
                intent.getArrivalAt(), intent.getStatus());
    }
}
