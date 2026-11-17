package com.citypass.movilidad.service;

import java.util.List;

/**
 * Nivel de respuesta acumulativo ante una alerta climática. El orden de declaración va de menos a más
 * restrictivo y se usa para comparar alertas que se superponen: no reordenar.
 */
public enum ClimateResponse {
    AVISO(List.of("AVISO_USUARIOS")),
    CONFIRMACION(List.of("AVISO_USUARIOS", "CONFIRMACION_RIESGO_AL_ALQUILAR")),
    BLOQUEO(List.of("AVISO_USUARIOS", "BLOQUEO_ALQUILER")),
    BLOQUEO_Y_AVISO_VIAJES(List.of("AVISO_USUARIOS", "BLOQUEO_ALQUILER", "AVISO_DEVOLUCION_VIAJES_ACTIVOS"));

    private final List<String> publishedActions;

    ClimateResponse(List<String> publishedActions) {
        this.publishedActions = publishedActions;
    }

    /** No se puede iniciar un viaje desde la estación; la devolución siempre se permite. */
    public boolean blocksRental() {
        return this == BLOQUEO || this == BLOQUEO_Y_AVISO_VIAJES;
    }

    /** Al alquilar hay que aceptar explícitamente el riesgo climático. */
    public boolean requiresRiskConfirmation() {
        return this == CONFIRMACION;
    }

    /** Se avisa a los viajes activos que salieron de una estación afectada. */
    public boolean notifiesActiveTrips() {
        return this == BLOQUEO_Y_AVISO_VIAJES;
    }

    /** Las alertas programadas se muestran por adelantado salvo las de simple aviso. */
    public boolean showsEarlyWarning() {
        return this != AVISO;
    }

    /** Valores de accionesAplicadas en AlertaClimaticaAplicada (contrato con Espacios: no cambiar los strings). */
    public List<String> publishedActions() {
        return publishedActions;
    }
}
