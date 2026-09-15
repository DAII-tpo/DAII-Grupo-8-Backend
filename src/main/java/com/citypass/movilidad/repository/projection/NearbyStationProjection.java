package com.citypass.movilidad.repository.projection;

import java.math.BigDecimal;

/**
 * Estación devuelta por la búsqueda por cercanía, con la distancia en metros ya calculada
 * por MySQL.
 */
public interface NearbyStationProjection {

    Long getId();

    String getName();

    String getAddress();

    BigDecimal getLatitude();

    BigDecimal getLongitude();

    Integer getCapacity();

    Double getDistanceMeters();
}
