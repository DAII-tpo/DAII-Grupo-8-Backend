package com.citypass.movilidad.model.enums;

/** Por qué no hay una estación para recomendar. */
public enum NoRecommendationReason {

    /** No hay ninguna estación habilitada dentro del radio de búsqueda. */
    NO_CANDIDATES,

    /** Hay estaciones cerca, pero ninguna tiene el recurso que el usuario necesita. */
    NO_VIABLE_STATION
}
