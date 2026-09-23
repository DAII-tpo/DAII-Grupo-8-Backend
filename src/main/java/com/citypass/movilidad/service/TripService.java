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
 * Ciclo de vida de un viaje: inicio (MOV-026), consulta del viaje activo (MOV-027),
 * finalización (MOV-028) e historial de viajes finalizados (MOV-030).
 *
 * Cada operación que modifica el viaje y la bicicleta corre en una sola transacción: si falla
 * cualquier validación no queda ningún cambio parcial. Los locks pesimistas (usuario, viaje,
 * bicicleta y estación destino) evitan que dos solicitudes concurrentes dejen al usuario con dos
 * viajes activos, usen la misma bicicleta a la vez o superen la capacidad de una estación.
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

    public Optional<TripResponse> findActiveTrip(Long userId) {
        existingUser(userId);
        return activeTripOf(userId).map(this::toResponse);
    }

    /**
     * Historial paginado de los viajes finalizados del usuario (MOV-030), del más reciente al más
     * antiguo.
     *
     * El filtro por usuario va dentro de la consulta y no sobre el resultado: un usuario nunca
     * puede ver los viajes de otro. Un usuario sin viajes finalizados recibe una página vacía, que
     * es el caso esperado de un usuario nuevo y no un error.
     */
    public PagedResponse<TripResponse> findTripHistory(Long userId, int page, int size) {
        existingUser(userId);
        Page<TripResponse> history = tripRepository
                .findByUserIdAndStatusOrderByStartedAtDescIdDesc(
                        userId, TripStatus.COMPLETED, PageRequest.of(page, size))
                .map(this::toResponse);
        return PagedResponse.of(history);
    }

    /**
     * No exige que el usuario siga ACTIVE: si fue bloqueado durante el viaje igual tiene que poder
     * devolver la bicicleta.
     */
    @Transactional
    public TripResponse endTrip(Long userId, Long tripId, TripEndRequest request) {
        User user = lockUser(userId);
        // El viaje de otro usuario se informa como inexistente para no revelar que existe.
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
        // Una incidencia reportada durante el viaje no cambia el estado de la bicicleta IN_USE
        // (ver BikeService.reportIncidentOnBike): recién al devolverla se la saca de circulación.
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
