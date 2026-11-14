package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.FestivalRestockTaskResponse;
import com.citypass.movilidad.eventbus.consume.InvalidEventException;
import com.citypass.movilidad.exception.BusinessRuleException;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.repository.FestivalRestockTaskRepository;
import com.citypass.movilidad.repository.StationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

@Service
public class FestivalRestockService {
    private static final int MIN_CAPACITY = 1000;
    private static final int RADIUS_METERS = 300;

    private final NearbyStationService stations;
    private final FestivalRestockTaskRepository tasks;
    private final Clock clock;
    private final StationRepository stationRepository;

    public FestivalRestockService(NearbyStationService stations, FestivalRestockTaskRepository tasks, Clock clock,
                                  StationRepository stationRepository) {
        this.stations = stations;
        this.tasks = tasks;
        this.clock = clock;
        this.stationRepository = stationRepository;
    }

    @Transactional
    public void planFromActivity(JsonNode data) {
        if (data == null || !data.isObject()) {
            throw new InvalidEventException("ActividadCreada: data inválido");
        }
        String state = requiredText(data, "estado", 32);
        long capacity = requiredInteger(data, "capacidad");
        if (!"PROGRAMADA".equals(state) || capacity < MIN_CAPACITY) {
            return;
        }

        String activityId = requiredText(data, "actividadId", 128);
        String title = requiredText(data, "titulo", 255);
        long start = requiredInteger(data, "fechaHoraInicio");
        long end = requiredInteger(data, "fechaHoraFinalizacion");
        if (end <= start) {
            throw new InvalidEventException("ActividadCreada: la finalización debe ser posterior al inicio");
        }
        long now = clock.millis();
        if (end <= now) {
            return;
        }
        JsonNode correlation = data.path("correlationId");
        String correlationId = correlation.isMissingNode() || correlation.isNull()
                ? "act-" + activityId : requiredText(data, "correlationId", 132);

        JsonNode location = data.path("ubicacion");
        double lat = requiredCoordinate(location, "lat", 90);
        double lng = requiredCoordinate(location, "lng", 180);

        for (var station : stations.findAllWithin(lat, lng, RADIUS_METERS)) {
            int target = (int) ((9L * station.capacity() + 9) / 10);
            tasks.createIfAbsent(activityId, correlationId, title, end, station.stationId(),
                    station.distanceMeters(), target, station.availableBikes() >= target ? now : null);
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<FestivalRestockTaskResponse> findUpcoming() {
        return tasks.findAll(clock.millis());
    }

    @Scheduled(fixedDelayString = "${movilidad.restock.reconcile-ms:5000}")
    @Transactional
    public void reconcile() {
        tasks.reconcile(clock.millis());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public FestivalRestockTaskResponse updateTarget(long id, int target) {
        long now = clock.millis();
        var task = requireTask(id, now);
        if (target < 1 || target > task.capacity()) {
            throw new BusinessRuleException("El objetivo debe estar entre 1 y la capacidad de la estación.");
        }
        if (tasks.updateTarget(id, target, now) == 0) {
            throw new BusinessRuleException("Solo se puede editar el objetivo de una tarea pendiente con estación activa.");
        }
        tasks.reconcile(clock.millis());
        return requireTask(id, clock.millis());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public FestivalRestockTaskResponse completeManually(long id) {
        long now = clock.millis();
        var task = requireTask(id, now);
        if ("COMPLETED".equals(task.status())) return task;
        if (tasks.completeManually(id, now) == 0) {
            throw new BusinessRuleException("Solo se pueden completar tareas pendientes.");
        }
        return requireTask(id, now);
    }

    public record TransferSuggestion(long stationId, String stationName, int bikes, int distanceMeters) {}

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<TransferSuggestion> suggestTransfer(long id) {
        long now = clock.millis();
        var task = requireTask(id, now);
        int needed = Math.min(task.targetBikes() - task.availableBikes(), task.availableSlots());
        if (!"PENDING".equals(task.status()) || needed <= 0) return List.of();
        var target = stationRepository.findByIdAndDeletedAtIsNull(task.stationId()).orElse(null);
        if (target == null || !"ACTIVE".equals(target.getStatus().name())) return List.of();
        var protectedStations = new HashSet<>(tasks.stationsWithUpcomingTasks(now));
        protectedStations.add(task.stationId());
        return stations.findAllWithin(target.getLatitude().doubleValue(), target.getLongitude().doubleValue(), 2000)
                .stream().filter(station -> !protectedStations.contains(station.stationId()))
                .map(station -> new TransferSuggestion(station.stationId(), station.stationName(),
                        Math.min(needed, station.availableBikes() - (station.capacity() + 1) / 2),
                        station.distanceMeters()))
                .filter(suggestion -> suggestion.bikes() > 0)
                .sorted(Comparator.comparingInt(TransferSuggestion::bikes).reversed()
                        .thenComparingInt(TransferSuggestion::distanceMeters)
                        .thenComparingLong(TransferSuggestion::stationId))
                .limit(1).toList();
    }

    private FestivalRestockTaskResponse requireTask(long id, long now) {
        return tasks.find(id, now)
                .orElseThrow(() -> new ResourceNotFoundException("Tarea de reabastecimiento no encontrada: " + id));
    }

    private static String requiredText(JsonNode data, String field, int maxLength) {
        JsonNode value = data.path(field);
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maxLength) {
            throw new InvalidEventException("ActividadCreada: " + field + " inválido");
        }
        return value.textValue();
    }

    private static long requiredInteger(JsonNode data, String field) {
        JsonNode value = data.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new InvalidEventException("ActividadCreada: " + field + " debe ser un entero");
        }
        return value.longValue();
    }

    private static double requiredCoordinate(JsonNode data, String field, double bound) {
        JsonNode value = data.path(field);
        double coordinate = value.asDouble(Double.NaN);
        if (!value.isNumber() || !Double.isFinite(coordinate) || Math.abs(coordinate) > bound) {
            throw new InvalidEventException("ActividadCreada: ubicación." + field + " inválida");
        }
        return coordinate;
    }
}
