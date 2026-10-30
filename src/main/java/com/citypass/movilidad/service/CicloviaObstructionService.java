package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.event.EmergenciaSolicitadaData;
import com.citypass.movilidad.dto.event.MovilidadObstruccionEvaluadaData;
import com.citypass.movilidad.dto.event.ReclamoActualizadoData;
import com.citypass.movilidad.dto.event.ReclamoCreadoData;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Caso de uso: Evaluación y gestión de impacto ciclista por estacionamiento indebido / obstrucción en ciclovía.
 * <p>
 * Al recibir com.citypass.reclamos.ReclamoCreado:
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

        // Validación de relevancia: afectaCirculacion o categorías/descripción relacionadas con tránsito o ciclovía
        boolean relevanteParaCiclovia = esRelevanteParaCiclovia(data);
        if (!relevanteParaCiclovia) {
            LOG.debug("Reclamo {} no afecta circulación ni ciclovías. Se omite evaluación.", data.reclamoId());
            return Optional.empty();
        }

        double lat = data.latitud() != null ? data.latitud() : 0.0;
        double lon = data.longitud() != null ? data.longitud() : 0.0;

        // Búsqueda de estaciones cercanas en radio de influencia
        List<Long> affectedStationIds = findAffectedStations(lat, lon);

        // Evaluación de nivel de impacto
        ObstructionImpactLevel impactLevel = evaluateImpactLevel(data, affectedStationIds.size());

        // Sugerencia de desvío
        String detour = buildDetourSuggestion(data);

        // Evaluación de riesgo / emergencia inmediata
        boolean requiresEmergency = isCriticalEmergency(data);

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

        if ("RESUELTO".equalsIgnoreCase(data.estadoNuevo())) {
            Optional<CicloviaObstruction> existing = obstructionRepository.findByReclamoId(data.reclamoId());
            if (existing.isPresent()) {
                CicloviaObstruction obstruction = existing.get();
                obstruction.setStatus(ObstructionStatus.RESOLVED);
                obstruction.setResolvedAt(clock.instant());
                obstructionRepository.save(obstruction);
                LOG.info("Obstrucción de ciclovía resuelta: reclamoId={}. Circulación ciclista normalizada.", data.reclamoId());
                return Optional.of(obstruction);
            }
        }
        return Optional.empty();
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

    private boolean esRelevanteParaCiclovia(ReclamoCreadoData data) {
        if (Boolean.TRUE.equals(data.afectaCirculacion())) {
            return true;
        }
        String texto = (safe(data.titulo()) + " " + safe(data.descripcion()) + " " + safe(data.categoria())).toLowerCase(Locale.ROOT);
        return texto.contains("ciclovia") || texto.contains("bicisenda") || texto.contains("bici") || texto.contains("transito");
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
        String prioridad = safe(data.prioridad()).toUpperCase(Locale.ROOT);
        if ("ALTA".equals(prioridad) || "CRITICA".equals(prioridad) || affectedStationsCount >= 3) {
            return ObstructionImpactLevel.ALTO;
        }
        if ("MEDIA".equals(prioridad) || affectedStationsCount >= 1) {
            return ObstructionImpactLevel.MEDIO;
        }
        return ObstructionImpactLevel.BAJO;
    }

    private boolean isCriticalEmergency(ReclamoCreadoData data) {
        String prioridad = safe(data.prioridad()).toUpperCase(Locale.ROOT);
        if ("ALTA".equals(prioridad) || "CRITICA".equals(prioridad)) {
            return true;
        }
        String texto = (safe(data.titulo()) + " " + safe(data.descripcion())).toLowerCase(Locale.ROOT);
        return texto.contains("peligro") || texto.contains("accidente") || texto.contains("choque") || texto.contains("riesgo");
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

