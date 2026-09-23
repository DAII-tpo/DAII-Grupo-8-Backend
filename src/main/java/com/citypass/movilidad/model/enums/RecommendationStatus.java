package com.citypass.movilidad.model.enums;

/** Resultado de una consulta de recomendación (MOV-042). */
public enum RecommendationStatus {

    /** Hay una estación recomendada. */
    RECOMMENDED,

    /** No se puede recomendar ninguna estación; el motivo viaja en el campo reason. */
    NO_RECOMMENDATION
}
