package com.citypass.movilidad.service;

import com.citypass.movilidad.client.RecommendationClient;
import com.citypass.movilidad.client.RecommendationUnavailableException;
import com.citypass.movilidad.client.dto.RecommendationApiRequest;
import com.citypass.movilidad.client.dto.RecommendationApiResponse.RankedStation;
import com.citypass.movilidad.dto.NearbyStationResponse;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import com.citypass.movilidad.model.enums.RecommendationStatus;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Component
class RestockDonorRanking {
    private final RecommendationClient client;

    RestockDonorRanking(RecommendationClient client) {
        this.client = client;
    }

    List<Long> rank(RecommendationApiRequest.UserLocation destination, List<RestockTransferPlanner.Route> routes,
                    Map<Long, FestivalRestockService.TransferSource> sources,
                    Map<Long, NearbyStationResponse> stations) {
        var snapshot = routes.stream().map(route -> {
            var station = stations.get(route.sourceId());
            // El recurso para decidir un retiro operativo es el excedente que el origen puede ceder.
            return new RecommendationApiRequest.StationSnapshot(station.stationId(),
                    station.latitude().doubleValue(), station.longitude().doubleValue(), station.capacity(),
                    sources.get(station.stationId()).transferableBikes(), station.availableSlots());
        }).toList();

        var response = client.recommend(new RecommendationApiRequest(RecommendationPurpose.PICKUP, destination, snapshot));
        if (response.purpose() != RecommendationPurpose.PICKUP
                || response.status() != RecommendationStatus.RECOMMENDED || response.ranking() == null) {
            throw new RecommendationUnavailableException("El modelo no devolvió un orden de orígenes");
        }
        var remaining = new HashSet<>(routes.stream().map(RestockTransferPlanner.Route::sourceId).toList());
        var order = response.ranking().stream()
                .sorted(Comparator.comparingInt(RankedStation::rank))
                .map(RankedStation::stationId).toList();
        if (order.size() != remaining.size() || !remaining.equals(new HashSet<>(order))) {
            throw new RecommendationUnavailableException("El orden de orígenes del modelo está incompleto");
        }
        return order;
    }
}
