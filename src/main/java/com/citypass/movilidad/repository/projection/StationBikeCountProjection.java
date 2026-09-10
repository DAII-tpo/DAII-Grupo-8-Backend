package com.citypass.movilidad.repository.projection;

/**
 * Conteo de bicicletas por estación, resuelto en una sola consulta agrupada para evitar N+1
 * cuando se pide la disponibilidad de varias estaciones (MOV-016 y, más adelante, MOV-017).
 */
public interface StationBikeCountProjection {

    Long getStationId();

    /** Bicicletas presentes en la estación en cualquier estado: son las que ocupan un anclaje. */
    Long getTotalBikes();

    /** Bicicletas en estado AVAILABLE: son las únicas que un usuario puede retirar. */
    Long getAvailableBikes();
}
