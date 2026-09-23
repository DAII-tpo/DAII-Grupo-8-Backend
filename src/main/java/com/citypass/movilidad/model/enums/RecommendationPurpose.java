package com.citypass.movilidad.model.enums;

/**
 * Para qué se pide la recomendación (MOV-042). Define cuál es el recurso que le importa al
 * usuario: bicicletas para retirar, anclajes libres para devolver.
 */
public enum RecommendationPurpose {

    /** Retirar una bicicleta: la estación tiene que tener bicicletas disponibles. */
    PICKUP,

    /** Devolver una bicicleta: la estación tiene que tener anclajes libres. */
    DROPOFF
}
