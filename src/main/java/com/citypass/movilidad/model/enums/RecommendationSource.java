package com.citypass.movilidad.model.enums;

/** Quién decidió la recomendación: el modelo o el criterio de respaldo. */
public enum RecommendationSource {

    /** Decidió el modelo de machine learning. */
    MODEL,

    /** El servicio no respondió: la estación más cercana con el recurso. */
    FALLBACK
}
