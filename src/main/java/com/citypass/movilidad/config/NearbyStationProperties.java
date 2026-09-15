package com.citypass.movilidad.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parámetros de la búsqueda de estaciones cercanas (MOV-017). Configurables por properties,
 * no hardcodeados: el radio operativo de 500 m es una decisión de negocio que puede cambiar.
 */
@ConfigurationProperties(prefix = "movilidad.stations.nearby")
public record NearbyStationProperties(
        @DefaultValue("500") int defaultRadiusMeters,
        @DefaultValue("5000") int maxRadiusMeters,
        @DefaultValue("10") int defaultLimit,
        @DefaultValue("50") int maxLimit) {
}
