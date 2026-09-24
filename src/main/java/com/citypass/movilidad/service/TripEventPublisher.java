package com.citypass.movilidad.service;

import com.citypass.movilidad.model.Trip;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Publica los eventos de inicio y fin de viaje.
 * Pendiente: publicar en el event bus del Grupo 1 cuando definan el contrato; por ahora solo se loguea.
 */
@Component
@Slf4j
public class TripEventPublisher {

    public void tripStarted(Trip trip) {
        log.info("Viaje iniciado: tripId={}, userId={}, bikeId={}, originStationId={}",
                trip.getId(), trip.getUser().getId(), trip.getBike().getId(), trip.getOriginStation().getId());
    }

    public void tripEnded(Trip trip) {
        log.info("Viaje finalizado: tripId={}, userId={}, bikeId={}, destinationStationId={}, durationSeconds={}",
                trip.getId(), trip.getUser().getId(), trip.getBike().getId(),
                trip.getDestinationStation().getId(), trip.getDurationSeconds());
    }
}
