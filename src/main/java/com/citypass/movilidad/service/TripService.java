package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.PagedResponse;
import com.citypass.movilidad.dto.TripEndRequest;
import com.citypass.movilidad.dto.TripResponse;
import com.citypass.movilidad.dto.TripStartRequest;
import com.citypass.movilidad.exception.BusinessRuleException;
import com.citypass.movilidad.exception.ClimateTripException;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.ClimateAlert;
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
import java.util.List;
import java.util.Optional;

/**
 * Ciclo de vida de un viaje: inicio, viaje activo, fin e historial.
 * Usa locks pesimistas para evitar viajes duplicados, bicis compartidas o estaciones sobrepasadas.
 */
@Service
@Transactional(readOnly = true)
public class TripService {

    static final List<TripStatus> FINISHED_STATUSES = List.of(TripStatus.COMPLETED, TripStatus.CLOSED_BY_EMERGENCY);

    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final BikeService bikeService;
    private final TripEventPublisher eventPublisher;
    private final BikeIncidentRepository incidentRepository;
    private final ClimatePolicyService climatePolicy;

    public TripService(TripRepository tripRepository, UserRepository userRepository,
                       BikeService bikeService, TripEventPublisher eventPublisher,
                       BikeIncidentRepository incidentRepository, ClimatePolicyService climatePolicy) {
        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
        this.bikeService = bikeService;
        this.eventPublisher = eventPublisher;
        this.incidentRepository = incidentRepository;
        this.climatePolicy = climatePolicy;
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
        if (emergencyTripOf(userId).isPresent()) {
            throw new BusinessRuleException("El usuario tiene un viaje pausado por una emergencia");
        }
        Bike bike = bikeService.lockActiveBike(request.bikeId());
        // Solo el retiro depende del clima: la devolución se permite siempre, aun en estaciones bloqueadas.
        climatePolicy.effective(bike.getStation())
                .ifPresent(climate -> checkClimatePolicy(climate, request));
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

    private void checkClimatePolicy(ClimatePolicyService.EffectiveClimate climate, TripStartRequest request) {
        ClimateAlert alert = climate.alert();
        ClimateResponse response = climate.response();
        String hint = ClimatePhenomenon.parse(alert.getPhenomenon())
                .map(phenomenon -> " (" + phenomenon.riskHint() + ")").orElse("");
        if (response.blocksRental()) {
            throw new ClimateTripException("ALQUILER_BLOQUEADO_POR_CLIMA",
                    "No se puede retirar una bicicleta por la alerta climática vigente" + hint, alert, response);
        }
        if (response.requiresRiskConfirmation() && !Boolean.TRUE.equals(request.aceptaRiesgoClimatico())) {
            throw new ClimateTripException("CONFIRMACION_RIESGO_CLIMATICO_REQUERIDA",
                    "Confirmá que aceptás el riesgo climático" + hint + " antes de iniciar el viaje", alert, response);
        }
    }

    /** Viaje en curso del usuario, si tiene: activo o pausado por una emergencia (status EMERGENCY). */
    public Optional<TripResponse> findActiveTrip(Long userId) {
        existingUser(userId);
        return activeTripOf(userId).or(() -> emergencyTripOf(userId)).map(this::toResponse);
    }

    /**
     * Historial paginado de viajes finalizados del usuario, del más reciente al más antiguo: los completados y
     * los cerrados sin cargo por una emergencia.
     */
    public PagedResponse<TripResponse> findTripHistory(Long userId, int page, int size) {
        existingUser(userId);
        Page<TripResponse> history = tripRepository
                .findByUserIdAndStatusInOrderByStartedAtDescIdDesc(
                        userId, FINISHED_STATUSES, PageRequest.of(page, size))
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
        if (trip.getStatus() == TripStatus.EMERGENCY) {
            throw new BusinessRuleException("El viaje está pausado por una emergencia: " + tripId);
        }
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

    private Optional<Trip> emergencyTripOf(Long userId) {
        return tripRepository.findByUserIdAndStatus(userId, TripStatus.EMERGENCY);
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
