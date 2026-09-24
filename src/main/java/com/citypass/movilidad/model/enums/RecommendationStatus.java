package com.citypass.movilidad.model.enums;

/** Resultado de una consulta de recomendación. */
public enum RecommendationStatus {

    /** Hay una estación recomendada. */
    RECOMMENDED,

    /** No hay estación para recomendar; el motivo va en reason. */
    NO_RECOMMENDATION
}
