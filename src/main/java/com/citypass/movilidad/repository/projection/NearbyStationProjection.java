package com.citypass.movilidad.repository.projection;

import java.math.BigDecimal;

/** Resultado de la búsqueda por cercanía, con la distancia en metros. */
public interface NearbyStationProjection {

    Long getId();

    String getName();

    String getAddress();

    BigDecimal getLatitude();

    BigDecimal getLongitude();

    Integer getCapacity();

    Double getDistanceMeters();
}
