package com.citypass.movilidad.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuración del embed de Analítica. */
@ConfigurationProperties("movilidad.analytics.embed")
public record AnalyticsProperties(String url,
                                  @DefaultValue("2000") int connectTimeoutMillis,
                                  @DefaultValue("5000") int readTimeoutMillis) {
}
