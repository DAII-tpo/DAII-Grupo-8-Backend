package com.citypass.movilidad.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parámetros de la integración con el servicio de recomendación (MOV-042).
 *
 * Los timeouts son cortos a propósito: la recomendación es una mejora de la experiencia, no un
 * dato imprescindible. Antes que hacer esperar al usuario, el backend responde con el criterio
 * de respaldo.
 */
@ConfigurationProperties(prefix = "movilidad.recommendation")
public record RecommendationProperties(

        @DefaultValue("http://localhost:8000") String baseUrl,
        @DefaultValue("1000") int connectTimeoutMillis,
        @DefaultValue("2000") int readTimeoutMillis,

        /** Radio dentro del cual se buscan estaciones candidatas para el snapshot. */
        @DefaultValue("1000") int candidateRadiusMeters,

        /** Tope de estaciones que se envían en el snapshot. */
        @DefaultValue("15") int maxCandidates,

        /**
         * Ping a /health al arrancar y después de cada respaldo. En el plan free de Render el
         * servicio se duerme sin tráfico y tarda 30-60 s en despertar.
         */
        @DefaultValue("true") boolean warmUpEnabled) {
}
