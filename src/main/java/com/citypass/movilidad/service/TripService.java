package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.PagedResponse;
import com.citypass.movilidad.dto.TripEndRequest;
import com.citypass.movilidad.dto.TripResponse;
import com.citypass.movilidad.dto.TripStartRequest;
import com.citypass.movilidad.exception.BusinessRuleException;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.BikeIncidentStatus;
import com.citypass.movilidad.model.enums.TripStatus;
import com.citypass.movilidad.model.enums.UserStatus;
import com.citypass.movilidad.repository.BikeIncidentRepository;
import com.citypass.movilidad.repository.TripRepository;
import com.citypass.movilidad.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Ciclo de vida de un viaje: inicio, viaje activo, fin e historial.
 * Usa locks pesimistas para evitar viajes duplicados, bicis compartidas o estaciones sobrepasadas.
 */
@Service
@Transactional(readOnly = true)
public class TripService {

    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final BikeService bikeService;
    private final TripEventPublisher eventPublisher;
    private final BikeIncidentRepository incidentRepository;

    public TripService(TripRepository tripRepository, UserRepository userRepository,
                       BikeService bikeService, TripEventPublisher eventPublisher,
                       BikeIncidentRepository incidentRepository) {
        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
        this.bikeService = bikeService;
        this.eventPublisher = eventPublisher;
        this.incidentRepository = incidentRepository;
    }

    @Transactional
    /** Inicia un viaje: el usuario tiene que estar activo, sin otro viaje, y la bici disponible. */
    public TripResponse startTrip(Long userId, TripStartRequest request) {
        User user = lockUser(userId);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessRuleException("El usuario no está habilitado para iniciar viajes: " + userId);
        }
        if (activeTripOf(userId).isPresent()) {
            throw new BusinessRuleException("El usuario ya tiene un viaje activo");
        }
        Bike bike = bikeService.lockActiveBike(request.bikeId());
        Station origin = bikeService.checkOutForTrip(bike, user);

        Trip trip = new Trip();
        trip.setUser(user);
        trip.setBike(bike);
        trip.setOriginStation(origin);
        trip.setStartedAt(Instant.now());
        trip.setStatus(TripStatus.ACTIVE);
        Trip saved = tripRepository.save(trip);
        eventPublisher.tripStarted(saved);
        return toResponse(saved);
    }

    /** Viaje activo del usuario, si tiene. */
    public Optional<TripResponse> findActiveTrip(Long userId) {
        existingUser(userId);
        return activeTripOf(userId).map(this::toResponse);
    }

    /** Historial paginado de viajes finalizados del usuario, del más reciente al más antiguo. */
    public PagedResponse<TripResponse> findTripHistory(Long userId, int page, int size) {
        existingUser(userId);
        Page<TripResponse> history = tripRepository
                .findByUserIdAndStatusOrderByStartedAtDescIdDesc(
                        userId, TripStatus.COMPLETED, PageRequest.of(page, size))
                .map(this::toResponse);
        return PagedResponse.of(history);
    }

    /** Finaliza el viaje devolviendo la bici. No exige usuario ACTIVE: un bloqueado igual puede devolverla. */
    @Transactional
    public TripResponse endTrip(Long userId, Long tripId, TripEndRequest request) {
        User user = lockUser(userId);
        // Un viaje de otro usuario se responde como inexistente.
        Trip trip = tripRepository.findByIdForUpdate(tripId)
                .filter(found -> found.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Viaje no encontrado: " + tripId));
        if (trip.getStatus() != TripStatus.ACTIVE) {
            throw new BusinessRuleException("El viaje no está activo: " + tripId);
        }
        Bike tripBike = trip.getBike();
        if (tripBike == null || tripBike.getId() == null) {
            throw new BusinessRuleException("El viaje no tiene una bicicleta válida asociada: " + tripId);
        }
        Bike bike = bikeService.lockActiveBike(tripBike.getId());
        if (!tripBike.getId().equals(bike.getId())) {
            throw new BusinessRuleException("La bicicleta es inconsistente con el viaje: " + tripId);
        }
        Station destination = bikeService.checkInFromTrip(bike, request.destinationStationId(), user);
        // Si se reportó una incidencia durante el viaje, la bici sale de circulación al devolverla.
        if (incidentRepository.existsByTripIdAndStatusIn(tripId, BikeIncidentStatus.PENDING)) {
            bikeService.reportIncidentOnBike(bike, user, BikeService.INCIDENT_REASON_PREFIX + " durante el viaje");
        }

        Instant endedAt = Instant.now();
        trip.setDestinationStation(destination);
        trip.setEndedAt(endedAt);
        trip.setDurationSeconds((int) Duration.between(trip.getStartedAt(), endedAt).toSeconds());
        trip.setStatus(TripStatus.COMPLETED);
        Trip saved = tripRepository.save(trip);
        eventPublisher.tripEnded(saved);
        return toResponse(saved);
    }

    private Optional<Trip> activeTripOf(Long userId) {
        return tripRepository.findByUserIdAndStatus(userId, TripStatus.ACTIVE);
    }

    private User lockUser(Long userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado: " + userId));
    }

    private void existingUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("Usuario no encontrado: " + userId);
        }
    }

    private TripResponse toResponse(Trip trip) {
        Bike bike = trip.getBike();
        Station origin = trip.getOriginStation();
        Station destination = trip.getDestinationStation();
        return new TripResponse(trip.getId(), trip.getStatus(), bike.getId(), bike.getCode(),
                origin.getId(), origin.getName(),
                destination == null ? null : destination.getId(),
                destination == null ? null : destination.getName(),
                trip.getStartedAt(), trip.getEndedAt(), trip.getDurationSeconds());
    }
}
