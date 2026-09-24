package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.StationRecommendationResponse.ComparedStation;
import com.citypass.movilidad.dto.StationRecommendationResponse.RecommendedStation;
import com.citypass.movilidad.model.enums.ExplanationCode;
import com.citypass.movilidad.model.enums.NoRecommendationReason;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import org.springframework.stereotype.Component;

/** Arma el mensaje que ve el usuario para cada resultado de la recomendación. */
@Component
public class RecommendationMessageComposer {

    /** Mensaje cuando recomendó el modelo, según el código de explicación. */
    public String forRecommendation(RecommendationPurpose purpose, ExplanationCode code,
                                    RecommendedStation best, ComparedStation nearest) {
        if (code == ExplanationCode.NEAREST_IS_BEST || nearest == null) {
            return "%s es la estación más cercana (%d m) y tiene %s."
                    .formatted(best.stationName(), best.distanceMeters(), units(purpose, unitsOf(purpose, best)));
        }

        int nearestUnits = unitsOf(purpose, nearest);
        int bestUnits = unitsOf(purpose, best);
        String detail = switch (code) {
            case NEAREST_HAS_NO_BIKES, NEAREST_NO_DOCKS ->
                    "%s no tiene %s; en %s hay %d."
                            .formatted(nearest.stationName(), resource(purpose, true),
                                    best.stationName(), bestUnits);
            case NEAREST_LOW_AVAILABILITY, NEAREST_FEW_DOCKS ->
                    "en %s %s %s y %s; en %s hay %d."
                            .formatted(nearest.stationName(), nearestUnits == 1 ? "queda solo" : "quedan solo",
                                    units(purpose, nearestUnits), risk(purpose, nearestUnits),
                                    best.stationName(), bestUnits);
            default ->
                    "en %s hay %s contra %d en %s, caminando %d m más."
                            .formatted(best.stationName(), units(purpose, bestUnits), nearestUnits,
                                    nearest.stationName(),
                                    Math.max(0, best.distanceMeters() - nearest.distanceMeters()));
        };

        return "Te recomendamos %s (%d m) en lugar de %s (%d m): %s"
                .formatted(best.stationName(), best.distanceMeters(),
                        nearest.stationName(), nearest.distanceMeters(), detail);
    }

    /** Mensaje cuando decidió el criterio de respaldo. */
    public String forFallback(RecommendationPurpose purpose, RecommendedStation best) {
        return "Te recomendamos %s (%d m), la estación más cercana con %s: hay %d."
                .formatted(best.stationName(), best.distanceMeters(), resource(purpose, true),
                        unitsOf(purpose, best));
    }

    /** Mensaje cuando no hay estación para recomendar. */
    public String forNoRecommendation(RecommendationPurpose purpose, NoRecommendationReason reason) {
        if (reason == NoRecommendationReason.NO_CANDIDATES) {
            return "No encontramos estaciones cerca de tu ubicación.";
        }
        return "Ninguna estación cerca tiene %s en este momento.".formatted(resource(purpose, true));
    }

    private int unitsOf(RecommendationPurpose purpose, RecommendedStation station) {
        return purpose == RecommendationPurpose.PICKUP ? station.availableBikes() : station.availableSlots();
    }

    private int unitsOf(RecommendationPurpose purpose, ComparedStation station) {
        return purpose == RecommendationPurpose.PICKUP ? station.availableBikes() : station.availableSlots();
    }

    /** "1 bicicleta" / "13 bicicletas" / "1 anclaje libre" / "17 anclajes libres". */
    private String units(RecommendationPurpose purpose, int amount) {
        return "%d %s".formatted(amount, resource(purpose, amount != 1));
    }

    private String resource(RecommendationPurpose purpose, boolean plural) {
        if (purpose == RecommendationPurpose.PICKUP) {
            return plural ? "bicicletas disponibles" : "bicicleta disponible";
        }
        return plural ? "anclajes libres" : "anclaje libre";
    }

    private String risk(RecommendationPurpose purpose, int amount) {
        if (purpose == RecommendationPurpose.PICKUP) {
            return amount == 1 ? "podría no estar cuando llegues" : "podrían no estar cuando llegues";
        }
        return amount == 1 ? "podría ocuparse antes de que llegues" : "podrían ocuparse antes de que llegues";
    }
}
