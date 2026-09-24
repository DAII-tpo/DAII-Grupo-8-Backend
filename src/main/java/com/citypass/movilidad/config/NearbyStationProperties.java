package com.citypass.movilidad.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Radio y límite (default y máximo) de la búsqueda de estaciones cercanas. */
@ConfigurationProperties(prefix = "movilidad.stations.nearby")
public record NearbyStationProperties(
        @DefaultValue("500") int defaultRadiusMeters,
        @DefaultValue("5000") int maxRadiusMeters,
        @DefaultValue("10") int defaultLimit,
        @DefaultValue("50") int maxLimit) {
}
