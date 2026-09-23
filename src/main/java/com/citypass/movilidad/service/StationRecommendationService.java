package com.citypass.movilidad.service;

import com.citypass.movilidad.client.RecommendationClient;
import com.citypass.movilidad.client.RecommendationUnavailableException;
import com.citypass.movilidad.client.dto.RecommendationApiRequest;
import com.citypass.movilidad.client.dto.RecommendationApiResponse;
import com.citypass.movilidad.config.RecommendationProperties;
import com.citypass.movilidad.dto.NearbyStationResponse;
import com.citypass.movilidad.dto.StationRecommendationResponse;
import com.citypass.movilidad.dto.StationRecommendationResponse.ComparedStation;
import com.citypass.movilidad.dto.StationRecommendationResponse.Explanation;
import com.citypass.movilidad.dto.StationRecommendationResponse.Factor;
import com.citypass.movilidad.dto.StationRecommendationResponse.RecommendedStation;
import com.citypass.movilidad.model.enums.NoRecommendationReason;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import com.citypass.movilidad.model.enums.RecommendationSource;
import com.citypass.movilidad.model.enums.RecommendationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Recomendación de estación para el frontend (MOV-042).
 *
 * Arma el snapshot de estaciones candidatas con la búsqueda por cercanía de MOV-017 (que ya
 * excluye las estaciones deshabilitadas y las dadas de baja), se lo manda al servicio de
 * recomendación (MOV-041) y traduce su respuesta al contrato público.
 *
 * Regla de oro del ticket: una falla del servicio de recomendación no puede hacer caer el
 * módulo. Cualquier problema —caída, timeout, respuesta ilegible o una estación que no estaba
 * en el snapshot— termina en el criterio de respaldo, que es la estación más cercana con el
 * recurso que el usuario necesita.
 *
 * Sin @Transactional a propósito: la llamada HTTP no debe mantener abierta una transacción de
 * base de datos. La única consulta que hace ya abre y cierra la suya en NearbyStationService.
 */
@Service
public class StationRecommendationService {

    private static final Logger LOG = LoggerFactory.getLogger(StationRecommendationService.class);

    private static final int MAX_ALTERNATIVES = 2;

    private final NearbyStationService nearbyStationService;
    private final RecommendationClient recommendationClient;
    private final RecommendationMessageComposer messageComposer;
    private final RecommendationProperties properties;

    public StationRecommendationService(NearbyStationService nearbyStationService,
                                        RecommendationClient recommendationClient,
                                        RecommendationMessageComposer messageComposer,
                                        RecommendationProperties properties) {
        this.nearbyStationService = nearbyStationService;
        this.recommendationClient = recommendationClient;
        this.messageComposer = messageComposer;
        this.properties = properties;
    }

    public StationRecommendationResponse recommend(double latitude, double longitude,
                                                   RecommendationPurpose purpose) {
        RecommendationPurpose target = purpose == null ? RecommendationPurpose.PICKUP : purpose;

        List<NearbyStationResponse> candidates = nearbyStationService.findNearby(
                latitude, longitude, properties.candidateRadiusMeters(), properties.maxCandidates());

        // Sin candidatas no hay nada que rankear: no se molesta al servicio de recomendación.
        if (candidates.isEmpty()) {
            return noRecommendation(target, NoRecommendationReason.NO_CANDIDATES, RecommendationSource.FALLBACK);
        }

        try {
            RecommendationApiResponse response = recommendationClient.recommend(
                    toApiRequest(latitude, longitude, target, candidates));
            return fromModel(response, target, candidates);
        } catch (RecommendationUnavailableException ex) {
            LOG.warn("Recomendación resuelta con el criterio de respaldo: {}", ex.getMessage());
            // Puede estar dormido (plan free de Render): se lo despierta para la próxima consulta.
            recommendationClient.warmUp();
            return fallback(target, candidates);
        }
    }

    private RecommendationApiRequest toApiRequest(double latitude, double longitude,
                                                  RecommendationPurpose purpose,
                                                  List<NearbyStationResponse> candidates) {
        List<RecommendationApiRequest.StationSnapshot> snapshot = candidates.stream()
                .map(station -> new RecommendationApiRequest.StationSnapshot(
                        station.stationId(),
                        station.latitude().doubleValue(),
                        station.longitude().doubleValue(),
                        station.capacity(),
                        station.availableBikes(),
                        station.availableSlots()))
                .toList();
        return new RecommendationApiRequest(purpose,
                new RecommendationApiRequest.UserLocation(latitude, longitude), snapshot);
    }

    /**
     * Traduce la respuesta del modelo usando <strong>solo</strong> estaciones del snapshot: si
     * devolviera un id que no se envió, la respuesta no es confiable y se va al respaldo.
     */
    private StationRecommendationResponse fromModel(RecommendationApiResponse response,
                                                    RecommendationPurpose purpose,
                                                    List<NearbyStationResponse> candidates) {
        if (response.purpose() != purpose) {
            throw new RecommendationUnavailableException(
                    "El servicio respondió para un propósito distinto al consultado");
        }

        if (response.status() == RecommendationStatus.NO_RECOMMENDATION) {
            NoRecommendationReason reason = response.reason() == null
                    ? NoRecommendationReason.NO_VIABLE_STATION
                    : response.reason();
            return noRecommendation(purpose, reason, RecommendationSource.MODEL);
        }

        if (response.recommendation() == null || response.explanation() == null
                || response.explanation().code() == null) {
            throw new RecommendationUnavailableException(
                    "El servicio recomendó una estación sin los datos necesarios para justificarla");
        }

        Map<Long, NearbyStationResponse> byId = candidates.stream()
                .collect(Collectors.toMap(NearbyStationResponse::stationId, Function.identity()));

        RecommendedStation best = recommended(byId, response.recommendation());
        List<RecommendedStation> alternatives = response.alternatives() == null ? List.of()
                : response.alternatives().stream()
                        .filter(Objects::nonNull)
                        .map(alternative -> recommended(byId, alternative))
                        .toList();
        ComparedStation nearest = compared(byId, response.explanation().comparedTo());

        Explanation explanation = new Explanation(response.explanation().code(), nearest,
                factors(response.explanation().factors()));

        return new StationRecommendationResponse(
                RecommendationStatus.RECOMMENDED, RecommendationSource.MODEL, purpose, null,
                messageComposer.forRecommendation(purpose, response.explanation().code(), best, nearest),
                best, alternatives, explanation, response.modelVersion(), Instant.now());
    }

    /** Criterio de respaldo: la candidata más cercana que tenga el recurso necesario. */
    private StationRecommendationResponse fallback(RecommendationPurpose purpose,
                                                   List<NearbyStationResponse> candidates) {
        // findNearby ya las devuelve ordenadas por distancia, así que la primera viable es la mejor.
        List<NearbyStationResponse> viable = candidates.stream()
                .filter(station -> resourceUnits(purpose, station) > 0)
                .toList();

        if (viable.isEmpty()) {
            return noRecommendation(purpose, NoRecommendationReason.NO_VIABLE_STATION,
                    RecommendationSource.FALLBACK);
        }

        RecommendedStation best = recommended(viable.getFirst(), null);
        List<RecommendedStation> alternatives = viable.stream()
                .skip(1)
                .limit(MAX_ALTERNATIVES)
                .map(station -> recommended(station, null))
                .toList();

        return new StationRecommendationResponse(
                RecommendationStatus.RECOMMENDED, RecommendationSource.FALLBACK, purpose, null,
                messageComposer.forFallback(purpose, best),
                best, alternatives, null, null, Instant.now());
    }

    private StationRecommendationResponse noRecommendation(RecommendationPurpose purpose,
                                                           NoRecommendationReason reason,
                                                           RecommendationSource source) {
        return new StationRecommendationResponse(
                RecommendationStatus.NO_RECOMMENDATION, source, purpose, reason,
                messageComposer.forNoRecommendation(purpose, reason),
                null, List.of(), null, null, Instant.now());
    }

    private RecommendedStation recommended(Map<Long, NearbyStationResponse> byId,
                                           RecommendationApiResponse.ScoredStation scored) {
        return recommended(fromSnapshot(byId, scored.stationId()), scored.score());
    }

    private RecommendedStation recommended(NearbyStationResponse station, Double score) {
        return new RecommendedStation(station.stationId(), station.stationName(), station.address(),
                station.latitude(), station.longitude(), station.distanceMeters(), station.capacity(),
                station.availableBikes(), station.availableSlots(), score);
    }

    private ComparedStation compared(Map<Long, NearbyStationResponse> byId,
                                     RecommendationApiResponse.StationRef ref) {
        if (ref == null) {
            return null;
        }
        NearbyStationResponse station = fromSnapshot(byId, ref.stationId());
        return new ComparedStation(station.stationId(), station.stationName(), station.distanceMeters(),
                station.availableBikes(), station.availableSlots());
    }

    private NearbyStationResponse fromSnapshot(Map<Long, NearbyStationResponse> byId, long stationId) {
        NearbyStationResponse station = byId.get(stationId);
        if (station == null) {
            throw new RecommendationUnavailableException(
                    "El servicio devolvió una estación que no estaba en el snapshot enviado");
        }
        return station;
    }

    private List<Factor> factors(List<RecommendationApiResponse.Factor> factors) {
        if (factors == null) {
            return List.of();
        }
        return factors.stream()
                .filter(Objects::nonNull)
                .map(factor -> new Factor(factor.feature(), factor.impact()))
                .toList();
    }

    private int resourceUnits(RecommendationPurpose purpose, NearbyStationResponse station) {
        return purpose == RecommendationPurpose.PICKUP ? station.availableBikes() : station.availableSlots();
    }
}
