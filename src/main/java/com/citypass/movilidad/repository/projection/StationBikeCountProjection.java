package com.citypass.movilidad.repository.projection;

/** Conteo de bicis por estación, resuelto en una sola consulta agrupada. */
public interface StationBikeCountProjection {

    Long getStationId();

    /** Bicis presentes en cualquier estado (ocupan anclaje). */
    Long getTotalBikes();

    /** Bicis AVAILABLE (las que se pueden retirar). */
    Long getAvailableBikes();
}
