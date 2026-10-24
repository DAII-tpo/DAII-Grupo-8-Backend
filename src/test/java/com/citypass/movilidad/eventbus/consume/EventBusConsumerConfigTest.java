package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.eventbus.EventBusProperties;
import com.citypass.movilidad.eventbus.EventBusTestProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventBusConsumerConfigTest {

    @Test
    void conSaslSeAutenticaConOauthBearerContraElServicioDeIdentidad() {
        Map<String, Object> config = EventBusConsumerConfig.kafkaConfig(
                EventBusTestProperties.withConsumer("SASL_SSL", "grupo3", "secreto"));

        assertThat(config)
                .containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.mechanism", "OAUTHBEARER")
                .containsEntry("sasl.oauthbearer.token.endpoint.url", EventBusTestProperties.TOKEN_URL)
                .containsEntry("schema.registry.url", EventBusTestProperties.GATEWAY_URL)
                .containsEntry("sasl.login.callback.handler.class",
                        "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginCallbackHandler")
                .containsEntry("sasl.jaas.config",
                        "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required"
                                + " clientId=\"grupo3\" clientSecret=\"secreto\";");
    }

    @Test
    void escapaLasComillasDelSecretoEnLaConfiguracionJaas() {
        Map<String, Object> config = EventBusConsumerConfig.kafkaConfig(
                EventBusTestProperties.withConsumer("SASL_PLAINTEXT", "grupo3", "se\"cr\\eto"));

        assertThat((String) config.get("sasl.jaas.config")).contains("clientSecret=\"se\\\"cr\\\\eto\"");
    }

    @Test
    void sinSaslNoAgregaCredenciales() {
        Map<String, Object> config = EventBusConsumerConfig.kafkaConfig(
                EventBusTestProperties.withConsumer("PLAINTEXT", null, null));

        assertThat(config).containsEntry("security.protocol", "PLAINTEXT").doesNotContainKey("sasl.jaas.config");
    }

    @Test
    void conSaslExigeCredenciales() {
        EventBusProperties sinSecreto = EventBusTestProperties.withConsumer("SASL_SSL", "grupo3", " ");

        assertThatThrownBy(() -> EventBusConsumerConfig.kafkaConfig(sinSecreto))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EVENTBUS_CLIENT_SECRET");
    }
}
