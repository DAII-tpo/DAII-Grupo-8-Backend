package com.citypass.movilidad.model.enums;

/**
 * Quién decidió la recomendación (MOV-042). El frontend lo necesita para saber si la
 * respuesta trae explicación del modelo o si es el criterio de respaldo del backend.
 */
public enum RecommendationSource {

    /** Decidió el modelo de machine learning del servicio de recomendación (MOV-041). */
    MODEL,

    /** El servicio no respondió: decidió el backend con la estación más cercana con recurso. */
    FALLBACK
}
