package com.citypass.movilidad.service;

import com.citypass.movilidad.model.Trip;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Punto de extensión para publicar los eventos del ciclo de vida de un viaje.
 *
 * TODO (dependencia externa - Grupo 1): el contrato de eventos del event bus
 * (movilidad.viaje.iniciado / movilidad.viaje.finalizado) todavía no está definido. Hasta entonces
 * solo se deja registro en el log; no inventar un formato propio que después haya que migrar.
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
