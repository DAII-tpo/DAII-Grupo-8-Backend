package com.citypass.movilidad.eventbus.publish;

import com.citypass.movilidad.eventbus.EventBusProperties;
import com.citypass.movilidad.repository.EventOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/**
 * Publicación en el bus. Solo se activa con citypass.eventbus.publisher.enabled=true; apagada, los eventos
 * se siguen guardando en el outbox y se publican cuando se active.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "citypass.eventbus.publisher", name = "enabled", havingValue = "true")
public class EventBusPublisherConfig {

    @Bean
    public RestClient eventBusRestClient(EventBusProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMillis()))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMillis()));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    public ServiceTokenProvider serviceTokenProvider(@Qualifier("eventBusRestClient") RestClient restClient,
                                                     EventBusProperties properties) {
        // Falla al arrancar en lugar de acumular eventos que nunca se van a poder publicar.
        if (isBlank(properties.clientId()) || isBlank(properties.clientSecret())) {
            throw new IllegalStateException("La publicación en el bus está activada pero faltan "
                    + "EVENTBUS_CLIENT_ID / EVENTBUS_CLIENT_SECRET");
        }
        return new ServiceTokenProvider(restClient, properties, Clock.systemUTC());
    }

    @Bean
    public EventGatewayClient eventGatewayClient(@Qualifier("eventBusRestClient") RestClient restClient,
                                                 ServiceTokenProvider tokenProvider,
                                                 EventBusProperties properties) {
        return new EventGatewayClient(restClient, tokenProvider, properties);
    }

    @Bean
    public OutboxRelay outboxRelay(EventOutboxRepository outboxRepository, EventGatewayClient gatewayClient,
                                   TransactionTemplate transactionTemplate, EventBusProperties properties) {
        return new OutboxRelay(outboxRepository, gatewayClient, transactionTemplate, properties, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(prefix = "citypass.eventbus.publisher", name = "register-event-types",
            havingValue = "true", matchIfMissing = true)
    public EventTypeRegistrar eventTypeRegistrar(EventGatewayClient gatewayClient,
                                                 ResourcePatternResolver resourceResolver,
                                                 ObjectMapper objectMapper, EventBusProperties properties) {
        return new EventTypeRegistrar(gatewayClient, resourceResolver, objectMapper, properties);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
