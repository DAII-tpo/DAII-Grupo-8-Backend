package com.citypass.movilidad.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Opcional hasta que BI entregue la URL y registre las credenciales de servicio. */
@ConfigurationProperties("movilidad.analytics.embed")
public record AnalyticsProperties(String url, String tokenUrl, String issuer, String jwkSetUri,
                                  String clientId, String clientSecret,
                                  @DefaultValue("2000") int connectTimeoutMillis,
                                  @DefaultValue("5000") int readTimeoutMillis) {
}
