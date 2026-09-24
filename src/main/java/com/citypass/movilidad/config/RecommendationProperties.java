package com.citypass.movilidad.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuración del servicio de recomendación. Timeouts cortos: si tarda, se usa el respaldo. */
@ConfigurationProperties(prefix = "movilidad.recommendation")
public record RecommendationProperties(

        @DefaultValue("http://localhost:8000") String baseUrl,
        @DefaultValue("1000") int connectTimeoutMillis,
        @DefaultValue("2000") int readTimeoutMillis,

        /** Radio de búsqueda de estaciones candidatas. */
        @DefaultValue("1000") int candidateRadiusMeters,

        /** Máximo de candidatas que se envían al modelo. */
        @DefaultValue("15") int maxCandidates,

        /** Ping a /health para despertar el servicio (en Render free se duerme sin tráfico). */
        @DefaultValue("true") boolean warmUpEnabled) {
}
