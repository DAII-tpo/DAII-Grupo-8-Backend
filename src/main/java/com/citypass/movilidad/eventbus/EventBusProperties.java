package com.citypass.movilidad.eventbus;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Configuración de la integración con el bus de eventos CityPass+ (Grupo 1). */
@ConfigurationProperties(prefix = "citypass.eventbus")
public record EventBusProperties(

        /** Namespace del módulo. Sale del token, acá solo se usa para armar el fqn de lo que publicamos. */
        @DefaultValue("com.citypass.movilidad") String namespace,

        /** Base de la API REST del gateway, con el /api/v1. */
        @DefaultValue("http://localhost:8080/api/v1") String gatewayUrl,

        /** Endpoint client_credentials del servicio de identidad. */
        @DefaultValue("http://localhost:8083/oauth/token") String tokenUrl,

        String clientId,
        String clientSecret,

        @DefaultValue("2000") int connectTimeoutMillis,
        @DefaultValue("5000") int readTimeoutMillis,

        @DefaultValue Publisher publisher,
        @DefaultValue Consumer consumer) {

    /** Publicación por HTTP a partir del outbox. */
    public record Publisher(
            @DefaultValue("false") boolean enabled,

            /** Registra al arrancar los event types de classpath:eventbus/event-types que todavía no existan. */
            @DefaultValue("true") boolean registerEventTypes,

            @DefaultValue("5s") Duration pollInterval,

            /** Máximo de eventos que se publican por ciclo del relay. */
            @DefaultValue("20") int batchSize,

            /** Intentos antes de marcar el evento como FAILED. */
            @DefaultValue("10") int maxAttempts,

            /** Backoff exponencial entre reintentos: initialBackoff * 2^(intento - 1), con tope en maxBackoff. */
            @DefaultValue("5s") Duration initialBackoff,
            @DefaultValue("10m") Duration maxBackoff) {
    }

    /** Consumo directo desde el Kafka del bus. */
    public record Consumer(
            @DefaultValue("false") boolean enabled,

            /** SASL_PLAINTEXT en local, SASL_SSL en el bus desplegado. PLAINTEXT solo para tests. */
            @DefaultValue("SASL_PLAINTEXT") String securityProtocol,

            /** El gateway responde /schemas/ids/{id} como un Schema Registry de Confluent. */
            @DefaultValue("http://localhost:8080/api/v1") String schemaRegistryUrl,

            /** Reintentos locales de un evento cuyo handler falló, antes de registrarlo como FAILED. */
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("2s") Duration retryInterval) {
    }

    /** Fqn de un event type propio: es también el nombre del tópico. */
    public String fqn(String eventName) {
        return namespace + "." + eventName;
    }
}
