package com.citypass.movilidad.model.enums;

/** Motivo de la recomendación, según el estado actual de la estación más cercana. */
public enum ExplanationCode {

    /** La recomendada es también la más cercana. */
    NEAREST_IS_BEST,

    /** La más cercana no tiene bicicletas disponibles. */
    NEAREST_HAS_NO_BIKES,

    /** A la más cercana le quedan 1 o 2 bicicletas. */
    NEAREST_LOW_AVAILABILITY,

    /** La más cercana no tiene anclajes libres. */
    NEAREST_NO_DOCKS,

    /** A la más cercana le quedan 1 o 2 anclajes libres. */
    NEAREST_FEW_DOCKS,

    /** Hay bastante más disponibilidad a poca distancia extra. */
    BETTER_AVAILABILITY_NEARBY
}
