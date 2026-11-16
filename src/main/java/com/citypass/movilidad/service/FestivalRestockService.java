package com.citypass.movilidad.service;

import com.citypass.movilidad.client.RecommendationUnavailableException;
import com.citypass.movilidad.client.dto.RecommendationApiRequest.UserLocation;
import com.citypass.movilidad.dto.FestivalRestockTaskResponse;
import com.citypass.movilidad.dto.NearbyStationResponse;
import com.citypass.movilidad.eventbus.consume.InvalidEventException;
import com.citypass.movilidad.exception.BusinessRuleException;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.BikeRepository;
import com.citypass.movilidad.repository.FestivalRestockTaskRepository;
import com.citypass.movilidad.repository.FestivalRestockTaskRepository.Activity;
import com.citypass.movilidad.repository.StationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FestivalRestockService {
    private static final int MIN_CAPACITY = 1000;
    private static final int MODEL_CANDIDATE_LIMIT = 200;
    private static final long MODEL_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int SOURCE_RADIUS_METERS = 2000;

    private final NearbyStationService stations;
    private final FestivalRestockTaskRepository tasks;
    private final Clock clock;
    private final StationRepository stationRepository;
    private final BikeRepository bikes;
    private final RestockDonorRanking donorRanking;
    private final TransactionTemplate readSnapshot;

    public FestivalRestockService(NearbyStationService stations, FestivalRestockTaskRepository tasks, Clock clock,
                                  StationRepository stationRepository, BikeRepository bikes, RestockDonorRanking donorRanking,
                                  PlatformTransactionManager transactions) {
        this.stations = stations;
        this.tasks = tasks;
        this.clock = clock;
        this.stationRepository = stationRepository;
        this.bikes = bikes;
        this.donorRanking = donorRanking;
        this.readSnapshot = new TransactionTemplate(transactions);
        this.readSnapshot.setReadOnly(true);
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

        int coverage = coverageMeters(capacity);
        var activity = new Activity(activityId, correlationId, title, start, end, coverage);
        for (var station : stations.findAllWithin(lat, lng, coverage)) {
            createTask(activity, now, station);
        }
    }

    @Transactional
    public void applyActivityChange(JsonNode data) {
        if (data == null || !data.isObject()) {
            throw new InvalidEventException("ActividadModificada: data inválido");
        }
        String activityId = requiredText(data, "actividadId", 128);
        String state = requiredText(data, "estadoNuevo", 16);
        if (!List.of("PROGRAMADA", "SUSPENDIDA", "CANCELADA").contains(state)) {
            throw new InvalidEventException("ActividadModificada: estadoNuevo inválido");
        }
        long modifiedAt = requiredInteger(data, "modificadaEn");
        var reason = data.path("motivo");
        if (!reason.isMissingNode() && !reason.isNull() && (!reason.isTextual() || reason.textValue().length() > 500)) {
            throw new InvalidEventException("ActividadModificada: motivo inválido");
        }
        tasks.saveActivityChange(activityId, state, modifiedAt, reason.textValue());
    }

    @Transactional
    public void changeQaDeadline(String activityId, long newDeadline) {
        long now = clock.millis();
        var activity = requireQaActivity(activityId, now);
        if (activity.start() == null && newDeadline < activity.deadline()) {
            throw new BusinessRuleException("Creá una actividad QA nueva para adelantar el cierre.");
        }
        if (activity.start() != null && newDeadline <= activity.start()) {
            throw new BusinessRuleException("El cierre debe ser posterior al inicio de la actividad.");
        }
        if (newDeadline != activity.deadline()) tasks.changeQaDeadline(activityId, newDeadline, now);
    }

    @Transactional
    public void changeQaLocation(String activityId, double latitude, double longitude) {
        if (!Double.isFinite(latitude) || Math.abs(latitude) > 90
                || !Double.isFinite(longitude) || Math.abs(longitude) > 180) {
            throw new BusinessRuleException("Ingresá coordenadas válidas para la nueva ubicación.");
        }
        long now = clock.millis();
        var activity = requireQaActivity(activityId, now);
        var coverage = stations.findAllWithin(latitude, longitude, activity.coverageMeters());
        tasks.markOldCoverage(activityId, now);
        var updated = new Activity(activityId, activity.correlationId(), activity.title(), activity.start(),
                activity.deadline(), activity.coverageMeters());
        for (var station : coverage) {
            createTask(updated, now, station);
            tasks.includeInCoverage(activityId, station.stationId(), station.distanceMeters(),
                    activity, now);
        }
    }

    private FestivalRestockTaskRepository.QaActivity requireQaActivity(String id, long now) {
        return tasks.lockQaActivity(id).filter(activity -> activity.deadline() > now)
                .orElseThrow(() -> new BusinessRuleException("Elegí una actividad QA vigente y sin cancelar."));
    }

    private void createTask(Activity activity, long now, NearbyStationResponse station) {
        int target = (int) ((9L * station.capacity() + 9) / 10);
        tasks.createIfAbsent(activity, station.stationId(), station.distanceMeters(), target, station.availableBikes() >= target ? now : null);
    }

    static int coverageMeters(long capacity) {
        if (capacity >= 5000) return 750;
        if (capacity >= 2500) return 500;
        return 300;
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
        requireActiveActivity(task);
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
        requireActiveActivity(task);
        if ("COMPLETED".equals(task.status())) return task;
        if (tasks.completeManually(id, now) == 0) {
            throw new BusinessRuleException("Solo se pueden completar tareas pendientes.");
        }
        return requireTask(id, now);
    }

    public record TransferSuggestion(long stationId, String stationName, int bikes, int distanceMeters) {}

    public record TransferSource(long stationId, String stationName, long totalBikes, int availableBikes,
                                 int minimumBikes, int transferableBikes) {}

    public record PlannedTransfer(TransferSource source, long destinationId, String destinationName,
                                  int bikes, int distanceMeters, boolean sharedDestination) {}

    public record EventTransferPlan(String activityId, int plannedBikes, int remainingBikes,
                                    List<PlannedTransfer> transfers, PlanStrategy strategy) {}

    public enum PlanStrategy { DISTANCE, MODEL, NO_CHOICE, MODEL_UNAVAILABLE, COVERAGE_PROTECTED }

    private static final Comparator<FestivalRestockTaskResponse> PRIORITY =
            Comparator.comparing(FestivalRestockTaskResponse::deadline)
                    .thenComparingInt(FestivalRestockTaskResponse::availableBikes)
                    .thenComparing(Comparator.comparingInt(FestivalRestockService::shortage).reversed())
                    .thenComparingInt(FestivalRestockTaskResponse::distanceMeters)
                    .thenComparingLong(FestivalRestockTaskResponse::id);

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<TransferSuggestion> suggestTransfer(long id) {
        long now = clock.millis();
        var task = requireTask(id, now);
        if (!"PENDING".equals(task.status()) || shortage(task) == 0) return List.of();
        return buildTransferPlans(collectPlanningData(now), false).stream()
                .filter(plan -> plan.activityId().equals(task.activityId()))
                .flatMap(plan -> plan.transfers().stream())
                .filter(transfer -> transfer.destinationId() == task.stationId())
                .map(transfer -> new TransferSuggestion(transfer.source().stationId(), transfer.source().stationName(),
                        Math.min(transfer.bikes(), shortage(task)), transfer.distanceMeters()))
                .limit(1).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<EventTransferPlan> transferPlans(boolean useModel) {
        var data = readSnapshot.execute(transaction -> collectPlanningData(clock.millis()));
        return buildTransferPlans(data, useModel);
    }

    private record PlanningData(List<FestivalRestockTaskResponse> pending,
                                List<RestockTransferPlanner.Destination> destinations,
                                Map<Long, UserLocation> locations,
                                Map<Long, NearbyStationResponse> donorStations,
                                Map<Long, TransferSource> sources) {}

    private PlanningData collectPlanningData(long now) {
        var pending = tasks.findPending(now);
        var byStation = pending.stream().collect(Collectors.groupingBy(FestivalRestockTaskResponse::stationId));
        var upcomingTargets = tasks.highestUpcomingTargets(now);
        var donorStations = new HashMap<Long, NearbyStationResponse>();
        var destinations = new ArrayList<RestockTransferPlanner.Destination>();
        var locations = new HashMap<Long, UserLocation>();

        // Una estación puede tener tareas de varios eventos. Se repone hasta el mayor objetivo vigente.
        var prioritized = byStation.values().stream()
                .map(group -> group.stream().min(PRIORITY).orElseThrow())
                .sorted(PRIORITY).toList();
        for (var task : prioritized) {
            int needed = byStation.get(task.stationId()).stream().mapToInt(FestivalRestockService::shortage).max()
                    .orElseThrow();
            int limit = Math.min(needed, task.availableSlots());
            if (limit == 0) continue;
            var target = stationRepository.findByIdAndDeletedAtIsNull(task.stationId()).orElse(null);
            if (target == null || target.getStatus() != StationStatus.ACTIVE) continue;

            var routes = new ArrayList<RestockTransferPlanner.Route>();
            for (var source : stations.findAllWithin(target.getLatitude().doubleValue(),
                    target.getLongitude().doubleValue(), SOURCE_RADIUS_METERS)) {
                if (source.stationId() == task.stationId()
                        || source.availableBikes() <= minimum(source, upcomingTargets)) continue;
                donorStations.put(source.stationId(), source);
                routes.add(new RestockTransferPlanner.Route(source.stationId(), source.distanceMeters()));
            }
            routes.sort(Comparator.comparingInt(RestockTransferPlanner.Route::distanceMeters)
                    .thenComparingLong(RestockTransferPlanner.Route::sourceId));
            destinations.add(new RestockTransferPlanner.Destination(task.stationId(), limit, routes));
            locations.put(task.stationId(), new UserLocation(target.getLatitude().doubleValue(),
                    target.getLongitude().doubleValue()));
        }

        return new PlanningData(pending, destinations, locations, donorStations, describeSources(donorStations, upcomingTargets));
    }

    private record Allocation(List<RestockTransferPlanner.Transfer> moves, PlanStrategy strategy) {}

    private Allocation allocate(PlanningData data, Map<Long, Integer> budgets, boolean useModel) {
        var baseline = RestockTransferPlanner.allocate(data.destinations(), budgets);
        if (!useModel) return new Allocation(baseline, PlanStrategy.DISTANCE);

        var ranked = new ArrayList<RestockTransferPlanner.Destination>();
        boolean choseWithModel = false;
        long started = System.nanoTime();
        try {
            for (var destination : data.destinations()) {
                var routes = destination.routes().stream()
                        .filter(route -> budgets.getOrDefault(route.sourceId(), 0) > 0).toList();
                if (routes.size() > 1) {
                    if (System.nanoTime() - started >= MODEL_BUDGET_NANOS) {
                        return new Allocation(baseline, PlanStrategy.MODEL_UNAVAILABLE);
                    }
                    var candidates = routes.stream().limit(MODEL_CANDIDATE_LIMIT).toList();
                    var order = donorRanking.rank(data.locations().get(destination.stationId()), candidates,
                            data.sources(), data.donorStations());
                    var byId = routes.stream().collect(Collectors.toMap(
                            RestockTransferPlanner.Route::sourceId, Function.identity()));
                    var sorted = new ArrayList<RestockTransferPlanner.Route>();
                    order.forEach(id -> sorted.add(byId.remove(id)));
                    routes.stream().filter(route -> byId.containsKey(route.sourceId())).forEach(sorted::add);
                    routes = sorted;
                    choseWithModel = true;
                }
                ranked.add(new RestockTransferPlanner.Destination(destination.stationId(), destination.limit(), routes));
            }
        } catch (RecommendationUnavailableException ex) {
            return new Allocation(baseline, PlanStrategy.MODEL_UNAVAILABLE);
        }
        if (!choseWithModel) return new Allocation(baseline, PlanStrategy.NO_CHOICE);

        var suggested = RestockTransferPlanner.allocate(ranked, budgets);
        if (!countsByDestination(suggested).equals(countsByDestination(baseline))) {
            return new Allocation(baseline, PlanStrategy.COVERAGE_PROTECTED);
        }
        return new Allocation(suggested, PlanStrategy.MODEL);
    }

    private static Map<Long, Integer> countsByDestination(List<RestockTransferPlanner.Transfer> moves) {
        return moves.stream().collect(Collectors.groupingBy(RestockTransferPlanner.Transfer::destinationId,
                Collectors.summingInt(RestockTransferPlanner.Transfer::bikes)));
    }

    private List<EventTransferPlan> buildTransferPlans(PlanningData data, boolean useModel) {
        var pending = data.pending();
        var byStation = pending.stream().collect(Collectors.groupingBy(FestivalRestockTaskResponse::stationId));
        var sources = data.sources();
        var budgets = sources.values().stream()
                .collect(Collectors.toMap(TransferSource::stationId, TransferSource::transferableBikes));
        var allocation = allocate(data, budgets, useModel);
        var movesByDestination = new HashMap<Long, List<PlannedTransfer>>();
        for (var move : allocation.moves()) {
            var group = byStation.get(move.destinationId());
            var transfer = new PlannedTransfer(sources.get(move.route().sourceId()), move.destinationId(),
                    group.getFirst().stationName(), move.bikes(), move.route().distanceMeters(), group.size() > 1);
            movesByDestination.computeIfAbsent(move.destinationId(), stationId -> new ArrayList<>()).add(transfer);
        }

        return pending.stream()
                .collect(Collectors.groupingBy(FestivalRestockTaskResponse::activityId,
                        LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(event -> summarizePlan(event.getKey(), event.getValue(), movesByDestination, allocation.strategy()))
                .toList();
    }

    private Map<Long, TransferSource> describeSources(Map<Long, NearbyStationResponse> donors,
                                                      Map<Long, Integer> upcomingTargets) {
        if (donors.isEmpty()) return Map.of();
        return bikes.countBikesByStationIds(donors.keySet(), BikeStatus.AVAILABLE).stream().map(count -> {
            var donor = donors.get(count.getStationId());
            int available = Math.toIntExact(count.getAvailableBikes());
            int minimum = minimum(donor, upcomingTargets);
            return new TransferSource(donor.stationId(), donor.stationName(), count.getTotalBikes(),
                    available, minimum, Math.max(0, available - minimum));
        }).collect(Collectors.toMap(TransferSource::stationId, Function.identity()));
    }

    private static EventTransferPlan summarizePlan(String activityId, List<FestivalRestockTaskResponse> eventTasks,
                                                    Map<Long, List<PlannedTransfer>> movesByDestination, PlanStrategy strategy) {
        var transfers = new ArrayList<PlannedTransfer>();
        int planned = 0;
        int remaining = 0;
        for (var task : eventTasks) {
            var moves = movesByDestination.getOrDefault(task.stationId(), List.of());
            transfers.addAll(moves);
            int incoming = moves.stream().mapToInt(PlannedTransfer::bikes).sum();
            planned += incoming;
            remaining += Math.max(0, shortage(task) - incoming);
        }
        return new EventTransferPlan(activityId, planned, remaining, transfers, strategy);
    }

    private static int shortage(FestivalRestockTaskResponse task) {
        return Math.max(0, task.targetBikes() - task.availableBikes());
    }

    private static int minimum(NearbyStationResponse station, Map<Long, Integer> upcomingTargets) {
        int baseMinimum = (station.capacity() + 1) / 2;
        return Math.max(baseMinimum, upcomingTargets.getOrDefault(station.stationId(), 0));
    }

    private static void requireActiveActivity(FestivalRestockTaskResponse task) {
        if (task.activityChange() != null && !"PROGRAMADA".equals(task.activityChange().state())) {
            throw new BusinessRuleException("El evento está suspendido o cancelado.");
        }
    }

    private FestivalRestockTaskResponse requireTask(long id, long now) {
        return tasks.find(id, now)
                .orElseThrow(() -> new ResourceNotFoundException("Tarea de reabastecimiento no encontrada: " + id));
    }

    private static String requiredText(JsonNode data, String field, int maxLength) {
        JsonNode value = data.path(field);
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maxLength) {
            throw new InvalidEventException("Actividad: " + field + " inválido");
        }
        return value.textValue();
    }

    private static long requiredInteger(JsonNode data, String field) {
        JsonNode value = data.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new InvalidEventException("Actividad: " + field + " debe ser un entero");
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
