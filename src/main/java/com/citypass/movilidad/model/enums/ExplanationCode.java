package com.citypass.movilidad.model.enums;

/**
 * Motivo de la recomendación (MOV-041). Describe el estado <em>actual</em> de la estación más
 * cercana; nunca afirma nada sobre su historial.
 *
 * Los códigos reservados del servicio (NEAREST_USUALLY_EMPTY_AT_THIS_HOUR y
 * NEAREST_USUALLY_FULL_AT_THIS_HOUR) no se declaran a propósito: hoy no se emiten y no hay
 * mensaje que se pueda afirmar sin historial real. Si llegaran, la deserialización falla y la
 * recomendación cae al criterio de respaldo, que es el comportamiento seguro.
 */
public enum ExplanationCode {

    /** La recomendada es también la más cercana. */
    NEAREST_IS_BEST,

    /** La más cercana no tiene bicicletas disponibles. */
    NEAREST_HAS_NO_BIKES,

    /** A la más cercana le quedan 1 o 2 bicicletas: pueden agotarse antes de llegar. */
    NEAREST_LOW_AVAILABILITY,

    /** La más cercana no tiene anclajes libres. */
    NEAREST_NO_DOCKS,

    /** A la más cercana le quedan 1 o 2 anclajes libres: pueden ocuparse antes de llegar. */
    NEAREST_FEW_DOCKS,

    /** Hay bastante más disponibilidad a poca distancia extra. */
    BETTER_AVAILABILITY_NEARBY
}
