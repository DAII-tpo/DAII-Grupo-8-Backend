package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.eventbus.EventBusProperties;
import com.citypass.movilidad.repository.EventInboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginCallbackHandler;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * Consumo directo desde el Kafka del bus. Solo se activa con citypass.eventbus.consumer.enabled=true.
 * <p>
 * La configuración genérica del consumidor (grupo, offsets, deserializadores, ack-mode) está en
 * application.properties bajo spring.kafka; acá se agrega lo propio del bus: autenticación SASL/OAUTHBEARER
 * contra el servicio de identidad y la URL del registry de schemas.
 */
@Configuration
@ConditionalOnProperty(prefix = "citypass.eventbus.consumer", name = "enabled", havingValue = "true")
public class EventBusConsumerConfig {

    static final String SCHEMA_REGISTRY_URL = "schema.registry.url";

    @Bean
    public EventEnvelopeMapper eventEnvelopeMapper(ObjectMapper objectMapper) {
        return new EventEnvelopeMapper(objectMapper);
    }

    @Bean
    public EventDispatcher eventDispatcher(ObjectProvider<EventHandler> handlers, EventEnvelopeMapper envelopeMapper,
                                           EventInboxRepository inboxRepository) {
        return new EventDispatcher(handlers.orderedStream().toList(), envelopeMapper, inboxRepository,
                Clock.systemUTC());
    }

    @Bean
    public FailedEventRecorder failedEventRecorder(EventEnvelopeMapper envelopeMapper,
                                                   EventInboxRepository inboxRepository) {
        return new FailedEventRecorder(envelopeMapper, inboxRepository, Clock.systemUTC());
    }

    /**
     * Reintenta en el momento un evento cuyo handler falló y, si sigue fallando, lo registra como FAILED y
     * sigue con el próximo. Spring Boot lo asigna al container factory por ser el único CommonErrorHandler.
     */
    @Bean
    public DefaultErrorHandler eventBusErrorHandler(FailedEventRecorder recorder, EventBusProperties properties) {
        EventBusProperties.Consumer settings = properties.consumer();
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recorder::recover,
                new FixedBackOff(settings.retryInterval().toMillis(), Math.max(0, settings.maxAttempts() - 1)));
        errorHandler.addNotRetryableExceptions(InvalidEventException.class);
        errorHandler.setCommitRecovered(true);
        return errorHandler;
    }

    @Bean
    public DefaultKafkaConsumerFactoryCustomizer eventBusConsumerCustomizer(EventBusProperties properties) {
        Map<String, Object> config = kafkaConfig(properties);
        return consumerFactory -> consumerFactory.updateConfigs(config);
    }

    @Bean
    public EventBusListener eventBusListener(ConcurrentKafkaListenerContainerFactory<Object, Object> containerFactory,
                                             EventDispatcher dispatcher) {
        return new EventBusListener(containerFactory, dispatcher);
    }

    static Map<String, Object> kafkaConfig(EventBusProperties properties) {
        EventBusProperties.Consumer settings = properties.consumer();
        Map<String, Object> config = new HashMap<>();
        config.put(SCHEMA_REGISTRY_URL, settings.schemaRegistryUrl());
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, settings.securityProtocol());

        if (settings.securityProtocol().startsWith("SASL")) {
            if (isBlank(properties.clientId()) || isBlank(properties.clientSecret())) {
                throw new IllegalStateException("El consumo del bus está activado pero faltan "
                        + "EVENTBUS_CLIENT_ID / EVENTBUS_CLIENT_SECRET");
            }
            // El cliente de Kafka pide el token y lo renueva solo contra el servicio de identidad.
            config.put(SaslConfigs.SASL_MECHANISM, "OAUTHBEARER");
            config.put(SaslConfigs.SASL_LOGIN_CALLBACK_HANDLER_CLASS, OAuthBearerLoginCallbackHandler.class.getName());
            config.put(SaslConfigs.SASL_OAUTHBEARER_TOKEN_ENDPOINT_URL, properties.tokenUrl());
            config.put(SaslConfigs.SASL_JAAS_CONFIG, OAuthBearerLoginModule.class.getName() + " required"
                    + " clientId=\"" + jaasQuote(properties.clientId()) + "\""
                    + " clientSecret=\"" + jaasQuote(properties.clientSecret()) + "\";");
        }
        return config;
    }

    // Dentro de un valor JAAS entre comillas, la barra y la comilla se escapan.
    private static String jaasQuote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
