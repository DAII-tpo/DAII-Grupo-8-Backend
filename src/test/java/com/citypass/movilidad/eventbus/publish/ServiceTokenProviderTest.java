package com.citypass.movilidad.eventbus.publish;

import com.citypass.movilidad.eventbus.EventBusTestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ServiceTokenProviderTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    private MockRestServiceServer server;
    private MutableClock clock;
    private ServiceTokenProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new MutableClock(NOW);
        provider = new ServiceTokenProvider(builder.build(), EventBusTestProperties.properties(), clock);
    }

    @Test
    void pideElTokenConClientCredentialsYBasicAuth() {
        String basic = Base64.getEncoder().encodeToString("grupo3:secreto".getBytes(StandardCharsets.UTF_8));
        server.expect(once(), requestTo(EventBusTestProperties.TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + basic))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of("grant_type", "client_credentials")))
                .andRespond(tokenResponse("token-1", 900));

        assertThat(provider.token()).isEqualTo("token-1");
        server.verify();
    }

    @Test
    void reutilizaElTokenMientrasNoEstePorVencer() {
        server.expect(once(), requestTo(EventBusTestProperties.TOKEN_URL)).andRespond(tokenResponse("token-1", 900));

        provider.token();
        clock.advance(Duration.ofMinutes(10));

        assertThat(provider.token()).isEqualTo("token-1");
        server.verify();
    }

    @Test
    void renuevaElTokenAntesDeQueVenza() {
        server.expect(once(), requestTo(EventBusTestProperties.TOKEN_URL)).andRespond(tokenResponse("token-1", 900));
        server.expect(once(), requestTo(EventBusTestProperties.TOKEN_URL)).andRespond(tokenResponse("token-2", 900));

        provider.token();
        clock.advance(Duration.ofSeconds(880));

        assertThat(provider.token()).isEqualTo("token-2");
        server.verify();
    }

    @Test
    void invalidarFuerzaUnTokenNuevo() {
        server.expect(once(), requestTo(EventBusTestProperties.TOKEN_URL)).andRespond(tokenResponse("token-1", 900));
        server.expect(once(), requestTo(EventBusTestProperties.TOKEN_URL)).andRespond(tokenResponse("token-2", 900));

        provider.token();
        provider.invalidate();

        assertThat(provider.token()).isEqualTo("token-2");
    }

    @Test
    void credencialesRechazadasSeInformanComoEventGatewayException() {
        server.expect(once(), requestTo(EventBusTestProperties.TOKEN_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(provider::token)
                .isInstanceOf(EventGatewayException.class)
                .satisfies(ex -> assertThat(((EventGatewayException) ex).status()).isEqualTo(401));
    }

    @Test
    void unTokenVacioEsUnError() {
        server.expect(once(), requestTo(EventBusTestProperties.TOKEN_URL))
                .andRespond(withSuccess("{\"expires_in\": 900}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(provider::token).isInstanceOf(EventGatewayException.class);
    }

    private static ResponseCreator tokenResponse(String token, int expiresIn) {
        return withSuccess("""
                {"access_token": "%s", "token_type": "Bearer", "expires_in": %d}
                """.formatted(token, expiresIn), MediaType.APPLICATION_JSON);
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
