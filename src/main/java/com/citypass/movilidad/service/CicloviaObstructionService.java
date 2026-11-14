package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.event.EmergenciaSolicitadaData;
import com.citypass.movilidad.dto.event.MovilidadObstruccionEvaluadaData;
import com.citypass.movilidad.dto.event.ReclamoActualizadoData;
import com.citypass.movilidad.dto.event.ReclamoCreadoData;
import com.citypass.movilidad.dto.event.ReclamoReclasificadoData;
import com.citypass.movilidad.dto.response.CicloviaObstructionResponse;
import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.model.CicloviaObstruction;
import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.model.enums.ObstructionImpactLevel;
import com.citypass.movilidad.model.enums.ObstructionStatus;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.CicloviaObstructionRepository;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.projection.NearbyStationProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.Collection;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Caso de uso: Evaluación y gestión de impacto ciclista por estacionamiento indebido / obstrucción en ciclovía.
 * <p>
 * Al recibir com.citypass.reclamos.ReclamoCreado.v3:
 * 1. Determina estaciones de bicicletas cercanas potencialmente afectadas.
 * 2. Registra la obstrucción y genera sugerencias de desvío de bicisenda.
 * 3. Publica com.citypass.movilidad.MovilidadObstruccionEvaluada para EDA y Espacios Públicos (Grupo 7).
 * 4. Si la prioridad o riesgo es alto, publica com.citypass.movilidad.EmergenciaSolicitada para Emergencias (Grupo 6).
 * <p>
 * Al recibir com.citypass.reclamos.ReclamoActualizado con estadoNuevo = RESUELTO:
 * 1. Marca la obstrucción como resuelta y normaliza la circulación ciclista.
 */
@Service
public class CicloviaObstructionService {

    private static final Logger LOG = LoggerFactory.getLogger(CicloviaObstructionService.class);
    private static final int INFLUENCE_RADIUS_METERS = 500;
    private static final int MAX_AFFECTED_STATIONS = 10;

    private final CicloviaObstructionRepository obstructionRepository;
    private final StationRepository stationRepository;
    private final EventPublisher eventPublisher;
    private final Clock clock;

    public CicloviaObstructionService(CicloviaObstructionRepository obstructionRepository,
                                     StationRepository stationRepository,
                                     EventPublisher eventPublisher,
                                     Clock clock) {
        this.obstructionRepository = obstructionRepository;
        this.stationRepository = stationRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public Optional<CicloviaObstruction> procesarReclamoCreado(ReclamoCreadoData data) {
        if (data == null || data.reclamoId() == null) {
            LOG.warn("ReclamoCreado recibido nulo o sin reclamoId");
            return Optional.empty();
        }

        // Si ya existe la obstrucción registrada para este reclamo, no duplicamos
        Optional<CicloviaObstruction> existing = obstructionRepository.findByReclamoId(data.reclamoId());
        if (existing.isPresent()) {
            LOG.info("Obstrucción ya registrada para reclamoId={}", data.reclamoId());
            return existing;
        }

        // El contrato de Reclamos identifica explícitamente la ciclovía, sin depender del texto libre.
        boolean relevanteParaCiclovia = esRelevanteParaCiclovia(data);
        if (!relevanteParaCiclovia) {
            LOG.debug("Reclamo {} no afecta circulación ni ciclovías. Se omite evaluación.", data.reclamoId());
            return Optional.empty();
        }

        if (data.latitud() == null || data.longitud() == null
                || !Double.isFinite(data.latitud()) || !Double.isFinite(data.longitud())
                || data.latitud() < -90 || data.latitud() > 90
                || data.longitud() < -180 || data.longitud() > 180) {
            LOG.warn("Reclamo {} relevante para ciclovía sin coordenadas válidas; se omite evaluación",
                    data.reclamoId());
            return Optional.empty();
        }

        double lat = data.latitud();
        double lon = data.longitud();

        // Búsqueda de estaciones cercanas en radio de influencia
        List<Long> affectedStationIds = findAffectedStations(lat, lon);

        // Evaluación de nivel de impacto
        ObstructionImpactLevel impactLevel = evaluateImpactLevel(data, affectedStationIds.size());

        // Sugerencia de desvío
        String detour = buildDetourSuggestion(data);

        // Evaluación de riesgo / emergencia inmediata
        boolean requiresEmergency = isCriticalEmergency(data);
        boolean requiresTow = isTowRequired(data);

        // Persistencia de la obstrucción
        CicloviaObstruction obstruction = CicloviaObstruction.builder()
                .reclamoId(data.reclamoId())
                .ciudadanoId(data.ciudadanoId())
                .titulo(data.titulo())
                .descripcion(data.descripcion())
                .direccion(data.direccion())
                .barrio(data.barrio())
                .latitude(BigDecimal.valueOf(lat))
                .longitude(BigDecimal.valueOf(lon))
                .impactLevel(impactLevel)
                .status(ObstructionStatus.ACTIVE)
                .affectedStationIds(joinIds(affectedStationIds))
                .detourDescription(detour)
                .emergencyDispatched(requiresEmergency)
                .reclamoCategoria(data.categoria())
                .reclamoPrioridad(data.prioridad())
                .reclamoEstado(data.estado())
                .objetoAfectado(data.objetoAfectado())
                .afectaCirculacion(data.afectaCirculacion())
                .build();

        obstruction = obstructionRepository.save(obstruction);
        LOG.info("Obstrucción de ciclovía registrada: reclamoId={}, impacto={}, estacionesAfectadas={}",
                data.reclamoId(), impactLevel, affectedStationIds);

        // Publicar evento com.citypass.movilidad.MovilidadObstruccionEvaluada
        MovilidadObstruccionEvaluadaData evaluadaPayload = new MovilidadObstruccionEvaluadaData(
                data.reclamoId(),
                data.correlationId(),
                lat,
                lon,
                data.direccion(),
                data.barrio(),
                true,
                affectedStationIds,
                impactLevel.name(),
                detour,
                requiresEmergency,
                requiresTow,
                clock.millis()
        );
        eventPublisher.publish("MovilidadObstruccionEvaluada", AggregateType.INCIDENT, data.reclamoId(), evaluadaPayload);

        // Si hay riesgo crítico, publicar com.citypass.movilidad.EmergenciaSolicitada
        if (requiresEmergency) {
            EmergenciaSolicitadaData emergenciaPayload = new EmergenciaSolicitadaData(
                    data.reclamoId(),
                    data.correlationId(),
                    "com.citypass.movilidad",
                    "OBSTRUCCION_CICLOVIA_CRITICA",
                    data.prioridad() != null ? data.prioridad() : "ALTA",
                    "Vehículo estacionado indebidamente bloqueando ciclovía en " + (data.direccion() != null ? data.direccion() : "zona reportada"),
                    data.direccion(),
                    lat,
                    lon,
                    clock.millis()
            );
            eventPublisher.publish("EmergenciaSolicitada", AggregateType.INCIDENT, data.reclamoId(), emergenciaPayload);
            LOG.warn("Alerta de emergencia emitida para reclamoId={} por obstrucción de ciclovía de alto riesgo", data.reclamoId());
        }

        return Optional.of(obstruction);
    }

    @Transactional
    public Optional<CicloviaObstruction> procesarReclamoActualizado(ReclamoActualizadoData data) {
        if (data == null || data.reclamoId() == null) {
            return Optional.empty();
        }

        Optional<CicloviaObstruction> existing = obstructionRepository.findByReclamoId(data.reclamoId());
        if (existing.isPresent()) {
            CicloviaObstruction obstruction = existing.get();
            obstruction.setReclamoEstado(data.estadoNuevo());
            if ("RESUELTO".equalsIgnoreCase(data.estadoNuevo())) {
                obstruction.setStatus(ObstructionStatus.RESOLVED);
                obstruction.setResolvedAt(clock.instant());
                obstructionRepository.save(obstruction);
                LOG.info("Obstrucción de ciclovía resuelta: reclamoId={}. Circulación ciclista normalizada.", data.reclamoId());
                return Optional.of(obstruction);
            }
            obstructionRepository.save(obstruction);
            if ("RECIBIDO".equalsIgnoreCase(data.estadoNuevo())) {
                solicitarEmergenciaSiCorresponde(obstruction, data.correlationId());
            }
            return Optional.of(obstruction);
        }
        return Optional.empty();
    }

    @Transactional
    public Optional<CicloviaObstruction> procesarReclamoReclasificado(ReclamoReclasificadoData data) {
        if (data == null || data.reclamoId() == null) {
            LOG.warn("ReclamoReclasificado recibido nulo o sin reclamoId");
            return Optional.empty();
        }

        Optional<CicloviaObstruction> existing = obstructionRepository.findByReclamoId(data.reclamoId());
        if (existing.isEmpty()) {
            // No conocemos el estado vigente porque ReclamoReclasificado no lo incluye.
            // Se registra como pendiente; ReclamoActualizado con RECIBIDO habilitará el despacho.
            ReclamoCreadoData reclamoActualizado = new ReclamoCreadoData(
                    data.reclamoId(), data.ciudadanoId(), null, data.titulo(), data.descripcion(),
                    data.categoriaNueva(), data.prioridadNueva(), "EN_REVISION", data.reclasificadoPor(), null,
                    null, data.barrio(), data.latitud(), data.longitud(), data.objetoAfectadoNuevo(),
                    data.afectaCirculacionNueva(), data.correlationId(), data.reclasificadoAt()
            );
            return procesarReclamoCreado(reclamoActualizado);
        }

        CicloviaObstruction obstruction = existing.get();
        if (obstruction.getStatus() != ObstructionStatus.ACTIVE) {
            LOG.info("Se ignora reclasificación de obstrucción no activa: reclamoId={}", data.reclamoId());
            return Optional.of(obstruction);
        }

        double lat = data.latitud() != null ? data.latitud() : obstruction.getLatitude().doubleValue();
        double lon = data.longitud() != null ? data.longitud() : obstruction.getLongitude().doubleValue();
        if (!validCoordinates(lat, lon)) {
            LOG.warn("Reclasificación de reclamo {} sin coordenadas válidas; no se reevalúa", data.reclamoId());
            return Optional.of(obstruction);
        }

        ReclamoCreadoData reclamoActualizado = new ReclamoCreadoData(
                data.reclamoId(), data.ciudadanoId(), null, data.titulo(), data.descripcion(),
                data.categoriaNueva(), data.prioridadNueva(), safe(obstruction.getReclamoEstado()).isBlank()
                        ? "EN_REVISION" : obstruction.getReclamoEstado(),
                data.reclasificadoPor(), null, obstruction.getDireccion(), data.barrio(), lat, lon,
                data.objetoAfectadoNuevo(), data.afectaCirculacionNueva(), data.correlationId(), data.reclasificadoAt()
        );

        obstruction.setCiudadanoId(data.ciudadanoId());
        obstruction.setTitulo(data.titulo());
        obstruction.setDescripcion(data.descripcion());
        if (data.barrio() != null) obstruction.setBarrio(data.barrio());
        obstruction.setLatitude(BigDecimal.valueOf(lat));
        obstruction.setLongitude(BigDecimal.valueOf(lon));
        obstruction.setReclamoCategoria(data.categoriaNueva());
        obstruction.setReclamoPrioridad(data.prioridadNueva());
        obstruction.setObjetoAfectado(data.objetoAfectadoNuevo());
        obstruction.setAfectaCirculacion(data.afectaCirculacionNueva());

        if (!esRelevanteParaCiclovia(reclamoActualizado)) {
            obstruction.setStatus(ObstructionStatus.RESOLVED);
            obstruction.setResolvedAt(clock.instant());
            obstruction.setAffectedStationIds("");
            obstruction.setImpactLevel(ObstructionImpactLevel.BAJO);
            obstruction.setDetourDescription(null);
            obstruction = obstructionRepository.save(obstruction);
            eventPublisher.publish("MovilidadObstruccionEvaluada", AggregateType.INCIDENT, data.reclamoId(),
                    new MovilidadObstruccionEvaluadaData(data.reclamoId(), data.correlationId(), lat, lon,
                            obstruction.getDireccion(), obstruction.getBarrio(), false, List.of(), "BAJO", null,
                            false, false, clock.millis()));
            LOG.info("Reclasificación quitó el reclamo {} del alcance de Movilidad", data.reclamoId());
            return Optional.of(obstruction);
        }

        List<Long> affectedStationIds = findAffectedStations(lat, lon);
        ObstructionImpactLevel impactLevel = evaluateImpactLevel(reclamoActualizado, affectedStationIds.size());
        String detour = buildDetourSuggestion(reclamoActualizado);
        boolean requiresEmergency = isCriticalEmergency(reclamoActualizado);
        boolean requiresTow = isTowRequired(reclamoActualizado);
        boolean emergencyAlreadyDispatched = obstruction.isEmergencyDispatched();

        obstruction.setImpactLevel(impactLevel);
        obstruction.setAffectedStationIds(joinIds(affectedStationIds));
        obstruction.setDetourDescription(detour);
        obstruction.setEmergencyDispatched(emergencyAlreadyDispatched || requiresEmergency);
        obstruction = obstructionRepository.save(obstruction);

        eventPublisher.publish("MovilidadObstruccionEvaluada", AggregateType.INCIDENT, data.reclamoId(),
                new MovilidadObstruccionEvaluadaData(data.reclamoId(), data.correlationId(), lat, lon,
                        obstruction.getDireccion(), obstruction.getBarrio(), true, affectedStationIds,
                        impactLevel.name(), detour, requiresEmergency, requiresTow, clock.millis()));

        if (requiresEmergency && !emergencyAlreadyDispatched
                && "RECIBIDO".equalsIgnoreCase(safe(obstruction.getReclamoEstado()))) {
            publicarEmergencia(obstruction, data.correlationId(), data.prioridadNueva(), lat, lon);
        }
        return Optional.of(obstruction);
    }

    @Transactional(readOnly = true)
    public List<CicloviaObstructionResponse> obtenerObstruccionesActivas() {
        return obstructionRepository.findByStatus(ObstructionStatus.ACTIVE).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isStationAffected(Long stationId) {
        if (stationId == null) {
            return false;
        }
        List<CicloviaObstruction> active = obstructionRepository.findByStatus(ObstructionStatus.ACTIVE);
        return active.stream()
                .anyMatch(obs -> parseIds(obs.getAffectedStationIds()).contains(stationId));
    }

    @Transactional(readOnly = true)
    public Set<Long> affectedStationIds(Collection<Long> stationIds) {
        if (stationIds == null || stationIds.isEmpty()) return Set.of();
        return obstructionRepository.findByStatus(ObstructionStatus.ACTIVE).stream()
                .flatMap(obstruction -> parseIds(obstruction.getAffectedStationIds()).stream())
                .filter(stationIds::contains)
                .collect(Collectors.toSet());
    }

    private boolean esRelevanteParaCiclovia(ReclamoCreadoData data) {
        return "CICLOVIA".equalsIgnoreCase(safe(data.objetoAfectado()).trim())
                && Boolean.TRUE.equals(data.afectaCirculacion());
    }

    private List<Long> findAffectedStations(double lat, double lon) {
        if (lat == 0.0 && lon == 0.0) {
            return Collections.emptyList();
        }
        GeoBoundingBox box = GeoBoundingBox.around(lat, lon, INFLUENCE_RADIUS_METERS);
        List<NearbyStationProjection> nearby = stationRepository.findNearby(
                lat, lon,
                box.minLatitude(), box.maxLatitude(), box.minLongitude(), box.maxLongitude(),
                INFLUENCE_RADIUS_METERS, StationStatus.ACTIVE.name(), MAX_AFFECTED_STATIONS);

        return nearby.stream()
                .map(NearbyStationProjection::getId)
                .toList();
    }

    private ObstructionImpactLevel evaluateImpactLevel(ReclamoCreadoData data, int affectedStationsCount) {
        String prioridad = "EN_REVISION".equalsIgnoreCase(safe(data.estado()).trim())
                ? "" : safe(data.prioridad()).toUpperCase(Locale.ROOT);
        if ("ALTA".equals(prioridad) || "CRITICA".equals(prioridad) || affectedStationsCount >= 3) {
            return ObstructionImpactLevel.ALTO;
        }
        if ("MEDIA".equals(prioridad) || affectedStationsCount >= 1) {
            return ObstructionImpactLevel.MEDIO;
        }
        return ObstructionImpactLevel.BAJO;
    }

    private boolean isCriticalEmergency(ReclamoCreadoData data) {
        // Una prioridad de modelo en revisión humana no debe disparar despacho de emergencias.
        if ("EN_REVISION".equalsIgnoreCase(safe(data.estado()).trim())) {
            return false;
        }
        String prioridad = safe(data.prioridad()).toUpperCase(Locale.ROOT);
        return "ALTA".equals(prioridad) || "CRITICA".equals(prioridad);
    }

    private boolean isTowRequired(ReclamoCreadoData data) {
        return "CICLOVIA".equalsIgnoreCase(safe(data.objetoAfectado()).trim())
                && "TRANSITO".equalsIgnoreCase(safe(data.categoria()).trim())
                && Boolean.TRUE.equals(data.afectaCirculacion());
    }

    private void solicitarEmergenciaSiCorresponde(CicloviaObstruction obstruction, String correlationId) {
        String prioridad = safe(obstruction.getReclamoPrioridad()).toUpperCase(Locale.ROOT);
        if (obstruction.getStatus() != ObstructionStatus.ACTIVE || obstruction.isEmergencyDispatched()
                || !("ALTA".equals(prioridad) || "CRITICA".equals(prioridad))) {
            return;
        }
        obstruction.setEmergencyDispatched(true);
        obstructionRepository.save(obstruction);
        List<Long> stations = parseIds(obstruction.getAffectedStationIds());
        boolean requiresTow = "CICLOVIA".equalsIgnoreCase(safe(obstruction.getObjetoAfectado()).trim())
                && "TRANSITO".equalsIgnoreCase(safe(obstruction.getReclamoCategoria()).trim())
                && Boolean.TRUE.equals(obstruction.getAfectaCirculacion());
        eventPublisher.publish("MovilidadObstruccionEvaluada", AggregateType.INCIDENT, obstruction.getReclamoId(),
                new MovilidadObstruccionEvaluadaData(obstruction.getReclamoId(), correlationId,
                        obstruction.getLatitude().doubleValue(), obstruction.getLongitude().doubleValue(),
                        obstruction.getDireccion(), obstruction.getBarrio(), true, stations,
                        obstruction.getImpactLevel().name(), obstruction.getDetourDescription(),
                        true, requiresTow, clock.millis()));
        publicarEmergencia(obstruction, correlationId, prioridad,
                obstruction.getLatitude().doubleValue(), obstruction.getLongitude().doubleValue());
    }

    private void publicarEmergencia(CicloviaObstruction obstruction, String correlationId,
                                    String prioridad, double lat, double lon) {
        EmergenciaSolicitadaData payload = new EmergenciaSolicitadaData(
                obstruction.getReclamoId(), correlationId, "com.citypass.movilidad",
                "OBSTRUCCION_CICLOVIA_CRITICA", prioridad,
                safe(obstruction.getTitulo()) + ". " + safe(obstruction.getDescripcion()),
                obstruction.getDireccion(), lat, lon, clock.millis());
        eventPublisher.publish("EmergenciaSolicitada", AggregateType.INCIDENT, obstruction.getReclamoId(), payload);
        LOG.warn("Alerta de emergencia emitida para reclamoId={} con prioridad {}",
                obstruction.getReclamoId(), prioridad);
    }

    private static boolean validCoordinates(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private String buildDetourSuggestion(ReclamoCreadoData data) {
        String dir = data.direccion() != null ? data.direccion() : "tramo afectado";
        return "Obstrucción total de ciclovía en " + dir + ". Desvío preventivo sugerido por carril lateral o ciclovía paralela más cercana.";
    }

    private CicloviaObstructionResponse toResponse(CicloviaObstruction entity) {
        return new CicloviaObstructionResponse(
                entity.getId(),
                entity.getReclamoId(),
                entity.getCiudadanoId(),
                entity.getTitulo(),
                entity.getDescripcion(),
                entity.getDireccion(),
                entity.getBarrio(),
                entity.getLatitude(),
                entity.getLongitude(),
                entity.getImpactLevel(),
                entity.getStatus(),
                parseIds(entity.getAffectedStationIds()),
                entity.getDetourDescription(),
                entity.isEmergencyDispatched(),
                entity.getCreatedAt(),
                entity.getResolvedAt()
        );
    }

    private static String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static List<Long> parseIds(String joined) {
        if (joined == null || joined.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(joined.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .toList();
    }

    private static String safe(String str) {
        return str == null ? "" : str;
    }
}

