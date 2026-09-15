package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.BikeCreateRequest;
import com.citypass.movilidad.dto.BikeResponse;
import com.citypass.movilidad.dto.BikeStatusChangeRequest;
import com.citypass.movilidad.dto.BikeStatusHistoryResponse;
import com.citypass.movilidad.exception.BusinessRuleException;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.BikeStatusHistory;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.BikeRepository;
import com.citypass.movilidad.repository.BikeStatusHistoryRepository;
import com.citypass.movilidad.repository.StationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class BikeService {

    private final BikeRepository bikeRepository;
    private final StationRepository stationRepository;
    private final BikeStatusHistoryRepository historyRepository;

    public BikeService(BikeRepository bikeRepository, StationRepository stationRepository,
                       BikeStatusHistoryRepository historyRepository) {
        this.bikeRepository = bikeRepository;
        this.stationRepository = stationRepository;
        this.historyRepository = historyRepository;
    }

    @Transactional
    public BikeResponse create(BikeCreateRequest request) {
        String code = request.code().trim();
        if (bikeRepository.existsByCode(code)) {
            throw new BusinessRuleException("Ya existe una bicicleta con el código " + code);
        }

        BikeStatus initialStatus = request.status() == null ? BikeStatus.AVAILABLE : request.status();
        if (initialStatus == BikeStatus.IN_USE) {
            throw new BusinessRuleException("Una bicicleta no puede darse de alta en estado IN_USE");
        }
        Station station = request.stationId() == null ? null : activeStation(request.stationId());
        if (initialStatus == BikeStatus.AVAILABLE && station == null) {
            throw new BusinessRuleException("Una bicicleta AVAILABLE debe estar asignada a una estación");
        }
        if (station != null) {
            ensureCapacity(station);
        }

        Bike bike = new Bike();
        bike.setCode(code);
        bike.setStation(station);
        bike.setStatus(initialStatus);
        bike.setModel(trimToNull(request.model()));
        bike.setPurchaseDate(request.purchaseDate());
        Bike saved = bikeRepository.save(bike);
        recordStatusChange(saved, null, initialStatus, "Alta de bicicleta");
        return toResponse(saved);
    }

    public BikeResponse findById(Long id) {
        return toResponse(activeBike(id));
    }

    public List<BikeResponse> findByStation(Long stationId) {
        existingStation(stationId);
        return bikeRepository.findAllByStationIdAndDeletedAtIsNullOrderByCode(stationId).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<BikeResponse> findAvailable(Long stationId) {
        List<Bike> bikes;
        if (stationId == null) {
            bikes = bikeRepository.findAllByStatusAndDeletedAtIsNullOrderByCode(BikeStatus.AVAILABLE);
        } else {
            activeStation(stationId);
            bikes = bikeRepository.findAllByStationIdAndStatusAndDeletedAtIsNullOrderByCode(
                    stationId, BikeStatus.AVAILABLE);
        }
        return bikes.stream().map(this::toResponse).toList();
    }

    @Transactional
    public BikeResponse changeStatus(Long id, BikeStatusChangeRequest request) {
        Bike bike = activeBike(id);
        BikeStatus previousStatus = bike.getStatus();
        BikeStatus newStatus = request.status();
        if (!BikeStatusTransitionPolicy.isAllowedForAdministration(previousStatus, newStatus)) {
            throw new BusinessRuleException(
                    "Transición administrativa no permitida: " + previousStatus + " -> " + newStatus);
        }
        if (newStatus == BikeStatus.AVAILABLE && bike.getStation() == null) {
            throw new BusinessRuleException("Una bicicleta AVAILABLE debe estar asignada a una estación");
        }

        bike.setStatus(newStatus);
        if (previousStatus == BikeStatus.MAINTENANCE && newStatus == BikeStatus.AVAILABLE) {
            bike.setLastMaintenanceAt(Instant.now());
        }
        Bike saved = bikeRepository.save(bike);
        recordStatusChange(saved, previousStatus, newStatus, request.reason());
        return toResponse(saved);
    }

    @Transactional
    public BikeResponse transfer(Long id, Long stationId) {
        Bike bike = activeBike(id);
        if (bike.getStatus() == BikeStatus.IN_USE) {
            throw new BusinessRuleException("Una bicicleta IN_USE no puede trasladarse administrativamente");
        }
        Station destination = activeStation(stationId);
        if (bike.getStation() != null && bike.getStation().getId().equals(destination.getId())) {
            throw new BusinessRuleException("La bicicleta ya se encuentra en la estación indicada");
        }
        ensureCapacity(destination);
        bike.setStation(destination);
        return toResponse(bikeRepository.save(bike));
    }

    @Transactional
    public void delete(Long id) {
        Bike bike = activeBike(id);
        if (bike.getStatus() == BikeStatus.IN_USE) {
            throw new BusinessRuleException("Una bicicleta IN_USE no puede darse de baja");
        }
        BikeStatus previousStatus = bike.getStatus();
        bike.setStatus(BikeStatus.OUT_OF_SERVICE);
        bike.setDeletedAt(Instant.now());
        Bike saved = bikeRepository.save(bike);
        if (previousStatus != BikeStatus.OUT_OF_SERVICE) {
            recordStatusChange(saved, previousStatus, BikeStatus.OUT_OF_SERVICE, "Baja lógica de bicicleta");
        }
    }

    /*
     * Operaciones que dispara el ciclo de vida de un viaje (MOV-026 / MOV-028). No pasan por
     * BikeStatusTransitionPolicy: AVAILABLE <-> IN_USE no es una transición administrativa, solo
     * la puede producir un viaje. Se ejecutan dentro de la transacción de TripService.
     */

    @Transactional
    public Bike lockActiveBike(Long id) {
        return bikeRepository.findByIdAndDeletedAtIsNullForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bicicleta no encontrada: " + id));
    }

    /** Retira la bicicleta de su estación para un viaje y devuelve la estación de origen. */
    @Transactional
    public Station checkOutForTrip(Bike bike, User user) {
        if (bike.getStatus() != BikeStatus.AVAILABLE) {
            throw new BusinessRuleException(
                    "La bicicleta no está disponible: " + bike.getId() + " (" + bike.getStatus() + ")");
        }
        Station origin = bike.getStation();
        if (origin == null) {
            throw new BusinessRuleException("La bicicleta no está asignada a ninguna estación: " + bike.getId());
        }
        // Una bicicleta IN_USE no ocupa anclaje: station_id queda en NULL mientras dura el viaje (ver DER).
        bike.setStation(null);
        bike.setStatus(BikeStatus.IN_USE);
        Bike saved = bikeRepository.save(bike);
        recordStatusChange(saved, BikeStatus.AVAILABLE, BikeStatus.IN_USE, "Inicio de viaje", user);
        return origin;
    }

    /** Devuelve la bicicleta en la estación destino al finalizar un viaje y devuelve esa estación. */
    @Transactional
    public Station checkInFromTrip(Bike bike, Long stationId, User user) {
        if (bike.getStatus() != BikeStatus.IN_USE) {
            throw new BusinessRuleException(
                    "La bicicleta no está en uso: " + bike.getId() + " (" + bike.getStatus() + ")");
        }
        Station destination = stationRepository.findByIdAndDeletedAtIsNullForUpdate(stationId)
                .orElseThrow(() -> new ResourceNotFoundException("Estación no encontrada: " + stationId));
        if (destination.getStatus() != StationStatus.ACTIVE) {
            throw new BusinessRuleException("La estación no está habilitada: " + stationId);
        }
        ensureCapacity(destination);
        bike.setStation(destination);
        bike.setStatus(BikeStatus.AVAILABLE);
        Bike saved = bikeRepository.save(bike);
        recordStatusChange(saved, BikeStatus.IN_USE, BikeStatus.AVAILABLE, "Fin de viaje", user);
        return destination;
    }

    public List<BikeStatusHistoryResponse> history(Long id) {
        bikeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bicicleta no encontrada: " + id));
        return historyRepository.findAllByBikeIdOrderByChangedAtDesc(id).stream()
                .map(item -> new BikeStatusHistoryResponse(
                        item.getId(), item.getPreviousStatus(), item.getNewStatus(),
                        item.getChangedByUser() == null ? null : item.getChangedByUser().getId(),
                        item.getReason(), item.getChangedAt()))
                .toList();
    }

    private Bike activeBike(Long id) {
        return bikeRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bicicleta no encontrada: " + id));
    }

    private Station activeStation(Long id) {
        Station station = existingStation(id);
        if (station.getStatus() != StationStatus.ACTIVE) {
            throw new BusinessRuleException("La estación no está habilitada: " + id);
        }
        return station;
    }

    private Station existingStation(Long id) {
        return stationRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Estación no encontrada: " + id));
    }

    private void ensureCapacity(Station station) {
        if (bikeRepository.countByStationIdAndDeletedAtIsNull(station.getId()) >= station.getCapacity()) {
            throw new BusinessRuleException("La estación no tiene capacidad disponible: " + station.getId());
        }
    }

    private void recordStatusChange(Bike bike, BikeStatus previous, BikeStatus next, String reason) {
        recordStatusChange(bike, previous, next, reason, null);
    }

    private void recordStatusChange(Bike bike, BikeStatus previous, BikeStatus next, String reason,
                                    User changedBy) {
        BikeStatusHistory history = new BikeStatusHistory();
        history.setBike(bike);
        history.setPreviousStatus(previous);
        history.setNewStatus(next);
        history.setChangedByUser(changedBy);
        history.setReason(trimToNull(reason));
        historyRepository.save(history);
    }

    private BikeResponse toResponse(Bike bike) {
        Station station = bike.getStation();
        return new BikeResponse(bike.getId(), bike.getCode(), station == null ? null : station.getId(),
                station == null ? null : station.getName(), bike.getStatus(), bike.getModel(),
                bike.getPurchaseDate(), bike.getLastMaintenanceAt(), bike.getCreatedAt(), bike.getUpdatedAt());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
