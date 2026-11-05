package com.citypass.movilidad.eventbus.publish;

import com.citypass.movilidad.eventbus.EventBusProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Function;

/**
 * Cliente HTTP del event-gateway. Toda falla sale como EventGatewayException, que indica si se puede
 * reintentar. Un 401 se reintenta una vez con un token nuevo antes de informarse.
 */
public class EventGatewayClient {

    private static final int MAX_ERROR_BODY_LENGTH = 300;

    private final RestClient restClient;
    private final ServiceTokenProvider tokenProvider;
    private final String gatewayUrl;

    public EventGatewayClient(RestClient restClient, ServiceTokenProvider tokenProvider,
                              EventBusProperties properties) {
        this.restClient = restClient;
        this.tokenProvider = tokenProvider;
        this.gatewayUrl = properties.gatewayUrl();
    }

    /** Publica un evento. El body son solo los campos de negocio (data). Devuelve el eventId asignado. */
    public String publish(String fqn, String dataJson) {
        PublishResponse response = withToken(token -> restClient.post()
                .uri(gatewayUrl + "/event-types/{fqn}/events", fqn)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(dataJson)
                .retrieve()
                .body(PublishResponse.class));

        if (response == null || response.metadata() == null || response.metadata().eventId() == null) {
            // El gateway respondió 2xx: el evento quedó publicado aunque no podamos leer su id.
            return null;
        }
        return response.metadata().eventId();
    }

    /** true si el event type ya está registrado en el bus. */
    public boolean eventTypeExists(String fqn) {
        try {
            withToken(token -> restClient.get()
                    .uri(gatewayUrl + "/event-types/{fqn}", fqn)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .toBodilessEntity());
            return true;
        } catch (EventGatewayException ex) {
            if (ex.status() == HttpStatus.NOT_FOUND.value()) {
                return false;
            }
            throw ex;
        }
    }

    /** Registra un event type: {"name": "...", "fields": [...]}. El namespace lo pone el gateway desde el token. */
    public void registerEventType(String definitionJson) {
        withToken(token -> restClient.post()
                .uri(gatewayUrl + "/event-types")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(definitionJson)
                .retrieve()
                .toBodilessEntity());
    }

    private <T> T withToken(Function<String, T> call) {
        try {
            try {
                return call.apply(tokenProvider.token());
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() != HttpStatus.UNAUTHORIZED.value()) {
                    throw ex;
                }
                // El servicio de identidad pudo haber rotado la clave: un token nuevo suele alcanzar.
                tokenProvider.invalidate();
                return call.apply(tokenProvider.token());
            }
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            throw new EventGatewayException("El gateway respondió HTTP " + status + ": " + bodyOf(ex),
                    status, isRetryable(status), ex);
        } catch (RestClientException | HttpMessageConversionException ex) {
            throw new EventGatewayException("No se pudo contactar al gateway: " + ex.getMessage(), 0, true, ex);
        }
    }

    /**
     * Reintentables: lo que se arregla solo con el tiempo. El 404 también, porque el event type puede
     * registrarse después de que se generó el evento. El resto de los 4xx (schema inválido, tópico ajeno,
     * payload demasiado grande) no se arregla reintentando.
     */
    static boolean isRetryable(int status) {
        return status >= 500
                || status == HttpStatus.UNAUTHORIZED.value()
                || status == HttpStatus.NOT_FOUND.value()
                || status == HttpStatus.REQUEST_TIMEOUT.value()
                || status == HttpStatus.TOO_MANY_REQUESTS.value();
    }

    private static String bodyOf(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        return body.length() <= MAX_ERROR_BODY_LENGTH ? body : body.substring(0, MAX_ERROR_BODY_LENGTH) + "…";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PublishResponse(Metadata metadata) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Metadata(String eventId) {
        }
    }
}
