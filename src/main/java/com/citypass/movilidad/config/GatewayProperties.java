package com.citypass.movilidad.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Integración con el bus de eventos del Grupo 1 (CU-INT-01).
 * Con {@code enabled=false} los eventos quedan en el outbox y no se despachan:
 * así se puede desarrollar y testear sin el gateway levantado.
 */
@ConfigurationProperties(prefix = "movilidad.gateway")
public record GatewayProperties(

        @DefaultValue("false") boolean enabled,

        /** API del gateway, por ejemplo https://test.citypass.mrfranco.net.ar */
        @DefaultValue("http://localhost:8080") String baseUrl,

        /** Emisor de tokens: el Login Federado o el simulador del Grupo 1. */
        @DefaultValue("http://localhost:8083/oauth/token") String tokenUrl,

        @DefaultValue("svc-movilidad") String clientId,
        @DefaultValue("") String clientSecret,

        /** Namespace propio en el bus: prefijo de los tipos que publicamos. */
        @DefaultValue("com.citypass.movilidad") String namespace,

        /**
         * Secreto que viaja en la URL del webhook (?token=...). El dispatcher del
         * Grupo 1 no firma las entregas, así que es la forma de saber que vienen de ahí.
         */
        @DefaultValue("") String webhookToken,

        @DefaultValue("2000") int connectTimeoutMillis,
        @DefaultValue("5000") int readTimeoutMillis,

        /** Cada cuánto se vacía el outbox. */
        @DefaultValue("5000") long dispatchIntervalMillis,

        /** Intentos antes de dar un evento por FAILED (queda en la tabla para revisarlo). */
        @DefaultValue("10") int maxAttempts,

        /** Radio alrededor de un incidente activo dentro del cual no se recomiendan estaciones. */
        @DefaultValue("300") int incidentRadiusMeters) {
}
