package com.citypass.movilidad.eventbus;

import java.time.Duration;

/** Propiedades del bus con valores de prueba, para tests que no levantan Spring. */
public final class EventBusTestProperties {

    public static final String GATEWAY_URL = "http://gateway.test/api/v1";
    public static final String TOKEN_URL = "http://identidad.test/oauth/token";

    private EventBusTestProperties() {
    }

    public static EventBusProperties properties() {
        return withConsumer("SASL_PLAINTEXT", "grupo3", "secreto");
    }

    public static EventBusProperties withConsumer(String securityProtocol, String clientId, String clientSecret) {
        return new EventBusProperties("com.citypass.movilidad", GATEWAY_URL, TOKEN_URL, clientId, clientSecret,
                1000, 2000,
                new EventBusProperties.Publisher(true, true, Duration.ofSeconds(5), 20, 3,
                        Duration.ofSeconds(5), Duration.ofSeconds(30)),
                new EventBusProperties.Consumer(true, securityProtocol, GATEWAY_URL, 3, Duration.ofMillis(10)));
    }
}
