package com.citypass.movilidad.eventbus.publish;

import com.citypass.movilidad.eventbus.EventBusTestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EventGatewayClientTest {

    private static final String FQN = "com.citypass.movilidad.ViajeFinalizado";
    private static final String PUBLISH_URL = EventBusTestProperties.GATEWAY_URL + "/event-types/" + FQN + "/events";

    private MockRestServiceServer server;
    private ServiceTokenProvider tokenProvider;
    private EventGatewayClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tokenProvider = mock(ServiceTokenProvider.class);
        when(tokenProvider.token()).thenReturn("token-1");
        client = new EventGatewayClient(builder.build(), tokenProvider, EventBusTestProperties.properties());
    }

    @Test
    void publicaSoloLosDatosConElTokenDeServicioYDevuelveElEventId() {
        server.expect(once(), requestTo(PUBLISH_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-1"))
                .andExpect(content().json("{\"tripId\": 7}"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"metadata": {"eventId": "ca33dcbb-f10e-4cf0-b30c-6ebe1d0b91fa", "schemaId": 7},
                         "data": {"tripId": 7}}
                        """));

        assertThat(client.publish(FQN, "{\"tripId\": 7}")).isEqualTo("ca33dcbb-f10e-4cf0-b30c-6ebe1d0b91fa");
        server.verify();
    }

    @Test
    void anteUn401RenuevaElTokenYReintentaUnaVez() {
        when(tokenProvider.token()).thenReturn("token-viejo", "token-nuevo");
        server.expect(once(), requestTo(PUBLISH_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-viejo"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(once(), requestTo(PUBLISH_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-nuevo"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"metadata\": {\"eventId\": \"e-1\"}}"));

        assertThat(client.publish(FQN, "{}")).isEqualTo("e-1");
        verify(tokenProvider).invalidate();
        server.verify();
    }

    @Test
    void unPayloadQueNoCumpleElSchemaNoSeReintentaYTraeElDetalle() {
        server.expect(once(), requestTo(PUBLISH_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"title\": \"El evento no cumple el schema\", \"detail\": \"El campo 'tripId' esperaba long\"}"));

        assertThatThrownBy(() -> client.publish(FQN, "{}"))
                .isInstanceOf(EventGatewayException.class)
                .hasMessageContaining("El campo 'tripId' esperaba long")
                .satisfies(ex -> {
                    assertThat(((EventGatewayException) ex).status()).isEqualTo(400);
                    assertThat(((EventGatewayException) ex).retryable()).isFalse();
                });
    }

    @Test
    void sinRespuestaDelGatewayEsReintentable() {
        server.expect(once(), requestTo(PUBLISH_URL)).andRespond(withException(new IOException("Connection refused")));

        assertThatThrownBy(() -> client.publish(FQN, "{}"))
                .isInstanceOf(EventGatewayException.class)
                .satisfies(ex -> {
                    assertThat(((EventGatewayException) ex).status()).isZero();
                    assertThat(((EventGatewayException) ex).retryable()).isTrue();
                });
    }

    @ParameterizedTest
    @CsvSource({"400,false", "403,false", "413,false", "401,true", "404,true", "408,true", "429,true",
            "502,true", "503,true", "504,true"})
    void clasificaQueErroresSeReintentan(int status, boolean retryable) {
        assertThat(EventGatewayClient.isRetryable(status)).isEqualTo(retryable);
    }

    @Test
    void eventTypeExistsDistingueEntreRegistradoYNoRegistrado() {
        String typeUrl = EventBusTestProperties.GATEWAY_URL + "/event-types/" + FQN;
        server.expect(once(), requestTo(typeUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(typeUrl)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.eventTypeExists(FQN)).isTrue();
        assertThat(client.eventTypeExists(FQN)).isFalse();
    }

    @Test
    void eventTypeExistsPropagaOtrosErrores() {
        server.expect(once(), requestTo(EventBusTestProperties.GATEWAY_URL + "/event-types/" + FQN))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.eventTypeExists(FQN)).isInstanceOf(EventGatewayException.class);
    }

    @Test
    void registraElEventTypeConLaDefinicionTalCual() {
        String definition = "{\"name\": \"ViajeFinalizado\", \"fields\": [{\"name\": \"tripId\", \"type\": \"long\"}]}";
        server.expect(once(), requestTo(EventBusTestProperties.GATEWAY_URL + "/event-types"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-1"))
                .andExpect(content().json(definition))
                .andRespond(withStatus(HttpStatus.CREATED));

        client.registerEventType(definition);
        server.verify();
    }
}
