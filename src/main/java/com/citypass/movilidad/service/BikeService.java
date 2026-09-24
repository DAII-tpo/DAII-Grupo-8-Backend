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
import com.citypass.movilidad.model.enums.MaintenanceStatus;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.BikeRepository;
import com.citypass.movilidad.repository.BikeStatusHistoryRepository;
import com.citypass.movilidad.repository.MaintenanceRecordRepository;
import com.citypass.movilidad.repository.StationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Gestión de bicicletas: alta, consultas, estados, traslados, viajes, incidencias y mantenimiento. */
@Service
@Transactional(readOnly = true)
public class BikeService {

    // Prefijo del motivo en el historial: identifica que la bici salió de servicio por un reporte.
    public static final String INCIDENT_REASON_PREFIX = "Incidencia reportada";

    private final BikeRepository bikeRepository;
    private final StationRepository stationRepository;
    private final BikeStatusHistoryRepository historyRepository;
    private final MaintenanceRecordRepository maintenanceRepository;

    public BikeService(BikeRepository bikeRepository, StationRepository stationRepository,
                       BikeStatusHistoryRepository historyRepository,
                       MaintenanceRecordRepository maintenanceRepository) {
        this.bikeRepository = bikeRepository;
        this.stationRepository = stationRepository;
        this.historyRepository = historyRepository;
        this.maintenanceRepository = maintenanceRepository;
    }

    /** Da de alta una bici validando código único, estación activa y capacidad. */
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

    /** Todas las bicis vigentes, opcionalmente filtradas por estado. */
    public List<BikeResponse> findAll(BikeStatus status) {
        List<Bike> bikes = status == null
                ? bikeRepository.findAllForAdminList()
                : bikeRepository.findAllForAdminListByStatus(status);
        return bikes.stream().map(this::toResponse).toList();
    }

    public List<BikeResponse> findByStation(Long stationId) {
        existingStation(stationId);
        return bikeRepository.findAllByStationIdAndDeletedAtIsNullOrderByCode(stationId).stream()
                .map(this::toResponse)
                .toList();
    }

    /** Bicis disponibles de todas las estaciones o de una en particular. */
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

    /** Cambio de estado administrativo, validado contra BikeStatusTransitionPolicy. */
    @Transactional
    public BikeResponse changeStatus(Long id, BikeStatusChangeRequest request) {
        Bike bike = activeBike(id);
        BikeStatus previousStatus = bike.getStatus();
        BikeStatus newStatus = request.status();
        if (!BikeStatusTransitionPolicy.isAllowedForAdministration(previousStatus, newStatus)) {
            throw new BusinessRuleException(
                    "Transición administrativa no permitida: " + previousStatus + " -> " + newStatus);
        }
        // Con una orden abierta, solo sale de mantenimiento al finalizar la orden.
        if (previousStatus == BikeStatus.MAINTENANCE
                && maintenanceRepository.existsByBikeIdAndStatus(id, MaintenanceStatus.IN_PROGRESS)) {
            throw new BusinessRuleException(
                    "La bicicleta tiene un mantenimiento en curso: finalizalo desde Mantenimiento");
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

    /** Traslada la bici a otra estación activa con lugar. */
    @Transactional
    public BikeResponse transfer(Long id, Long stationId) {
        Bike bike = activeBike(id);
        if (bike.getStatus() == BikeStatus.IN_USE) {
            throw new BusinessRuleException("Una bicicleta IN_USE no puede trasladarse administrativamente");
        }
        if (bike.getStatus() == BikeStatus.MAINTENANCE
                && maintenanceRepository.existsByBikeIdAndStatus(id, MaintenanceStatus.IN_PROGRESS)) {
            throw new BusinessRuleException(
                    "Una bicicleta en mantenimiento no puede trasladarse: la estación se elige al finalizarlo");
        }
        Station destination = activeStation(stationId);
        if (bike.getStation() != null && bike.getStation().getId().equals(destination.getId())) {
            throw new BusinessRuleException("La bicicleta ya se encuentra en la estación indicada");
        }
        ensureCapacity(destination);
        bike.setStation(destination);
        return toResponse(bikeRepository.save(bike));
    }

    /** Baja lógica: la bici queda OUT_OF_SERVICE con fecha de baja. */
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

    // Operaciones internas de viajes, incidencias y mantenimiento: no pasan por la política administrativa.

    /** Obtiene la bici con lock pesimista, para operaciones que no pueden correr en paralelo. */
    @Transactional
    public Bike lockActiveBike(Long id) {
        return bikeRepository.findByIdAndDeletedAtIsNullForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bicicleta no encontrada: " + id));
    }

    /** Pasa la bici a MAINTENANCE y la retira de su estación. Devuelve la estación de origen. */
    @Transactional
    public Station sendToMaintenance(Bike bike, User admin, String reason) {
        BikeStatus previous = bike.getStatus();
        Station origin = bike.getStation();
        // Bicis que ya estaban en MAINTENANCE sin orden (versión anterior): solo se retiran de la estación.
        if (previous == BikeStatus.MAINTENANCE) {
            bike.setStation(null);
            bikeRepository.save(bike);
            return origin;
        }
        if (previous != BikeStatus.AVAILABLE && previous != BikeStatus.OUT_OF_SERVICE) {
            throw new BusinessRuleException("La bicicleta no puede enviarse a mantenimiento desde " + previous);
        }
        bike.setStatus(BikeStatus.MAINTENANCE);
        bike.setStation(null);
        Bike saved = bikeRepository.save(bike);
        recordStatusChange(saved, previous, BikeStatus.MAINTENANCE, reason, admin);
        return origin;
    }

    /** Vuelve la bici a AVAILABLE en la estación indicada (null: se queda en la que está). */
    @Transactional
    public void returnFromMaintenance(Bike bike, Long stationId, User admin, String reason) {
        if (bike.getStatus() != BikeStatus.MAINTENANCE) {
            throw new BusinessRuleException("La bicicleta no está en mantenimiento: " + bike.getId());
        }
        Station current = bike.getStation();
        if (stationId == null) {
            if (current == null) {
                throw new BusinessRuleException("Indicá la estación donde se devuelve la bicicleta");
            }
        } else if (current == null || !current.getId().equals(stationId)) {
            // Con lock para no superar la capacidad de la estación.
            Station destination = stationRepository.findByIdAndDeletedAtIsNullForUpdate(stationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Estación no encontrada: " + stationId));
            if (destination.getStatus() != StationStatus.ACTIVE) {
                throw new BusinessRuleException("La estación no está habilitada: " + stationId
                        + ". Indicá otra estación para devolver la bicicleta");
            }
            ensureCapacity(destination);
            bike.setStation(destination);
        }
        bike.setStatus(BikeStatus.AVAILABLE);
        bike.setLastMaintenanceAt(Instant.now());
        Bike saved = bikeRepository.save(bike);
        recordStatusChange(saved, BikeStatus.MAINTENANCE, BikeStatus.AVAILABLE, reason, admin);
    }

    /** Retira la bici de su estación al iniciar un viaje. Devuelve la estación de origen. */
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
        // Durante el viaje no ocupa anclaje.
        bike.setStation(null);
        bike.setStatus(BikeStatus.IN_USE);
        Bike saved = bikeRepository.save(bike);
        recordStatusChange(saved, BikeStatus.AVAILABLE, BikeStatus.IN_USE, "Inicio de viaje", user);
        return origin;
    }

    /**
     * Saca de circulación una bici reportada (queda OUT_OF_SERVICE). Si está en un viaje no se toca:
     * TripService la saca de circulación cuando se devuelve.
     */
    @Transactional
    public void reportIncidentOnBike(Bike bike, User user, String reason) {
        BikeStatus previous = bike.getStatus();
        if (previous == BikeStatus.AVAILABLE) {
            bike.setStatus(BikeStatus.OUT_OF_SERVICE);
            Bike saved = bikeRepository.save(bike);
            recordStatusChange(saved, previous, BikeStatus.OUT_OF_SERVICE, reason, user);
        }
    }

    /** Tras rechazar un reporte, vuelve la bici a AVAILABLE si había salido de servicio por ese reporte. */
    @Transactional
    public void returnToServiceAfterRejectedIncident(Long bikeId, User admin) {
        Bike bike = bikeRepository.findByIdAndDeletedAtIsNullForUpdate(bikeId).orElse(null);
        if (bike == null || bike.getStatus() != BikeStatus.OUT_OF_SERVICE || bike.getStation() == null) {
            return;
        }
        boolean outOfServiceByIncident = historyRepository.findFirstByBikeIdOrderByChangedAtDescIdDesc(bikeId)
                .filter(last -> last.getNewStatus() == BikeStatus.OUT_OF_SERVICE)
                .map(BikeStatusHistory::getReason)
                .filter(reason -> reason.startsWith(INCIDENT_REASON_PREFIX))
                .isPresent();
        if (!outOfServiceByIncident) {
            return;
        }
        bike.setStatus(BikeStatus.AVAILABLE);
        Bike saved = bikeRepository.save(bike);
        recordStatusChange(saved, BikeStatus.OUT_OF_SERVICE, BikeStatus.AVAILABLE, "Incidencia rechazada", admin);
    }

    /** Deja la bici en la estación destino al terminar un viaje. */
    @Transactional
    public Station checkInFromTrip(Bike bike, Long stationId, User user) {
        // MAINTENANCE: bicis que la versión anterior pasó a mantenimiento en pleno viaje.
        if (bike.getStatus() != BikeStatus.IN_USE && bike.getStatus() != BikeStatus.MAINTENANCE) {
            throw new BusinessRuleException(
                    "La bicicleta no está en uso: " + bike.getId() + " (" + bike.getStatus() + ")");
        }
        if (bike.getStation() != null) {
            throw new BusinessRuleException(
                    "La bicicleta en uso está asociada a una estación y es inconsistente con el viaje: "
                            + bike.getId());
        }
        Station destination = stationRepository.findByIdAndDeletedAtIsNullForUpdate(stationId)
                .orElseThrow(() -> new ResourceNotFoundException("Estación no encontrada: " + stationId));
        if (destination.getStatus() != StationStatus.ACTIVE) {
            throw new BusinessRuleException("La estación no está habilitada: " + stationId);
        }
        ensureCapacity(destination);
        bike.setStation(destination);
        if (bike.getStatus() != BikeStatus.MAINTENANCE) {
            bike.setStatus(BikeStatus.AVAILABLE);
            Bike saved = bikeRepository.save(bike);
            recordStatusChange(saved, BikeStatus.IN_USE, BikeStatus.AVAILABLE, "Fin de viaje", user);
        } else {
            bikeRepository.save(bike);
        }
        return destination;
    }

    /** Historial de cambios de estado, del más reciente al más antiguo. */
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

    // Todas las bicis presentes ocupan anclaje, sin importar su estado.
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
