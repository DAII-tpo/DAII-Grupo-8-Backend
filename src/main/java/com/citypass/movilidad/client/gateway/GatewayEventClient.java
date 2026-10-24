package com.citypass.movilidad.client.gateway;

import com.citypass.movilidad.config.GatewayProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Publica un evento en el gateway: POST /api/v1/event-types/{fqn}/events con solo
 * los campos de negocio. El gateway valida contra el schema Avro registrado y agrega
 * la metadata (eventId, source, receivedAt).
 */
@Component
public class GatewayEventClient {

    private final RestClient restClient;
    private final GatewayTokenProvider tokenProvider;
    private final GatewayProperties properties;

    public GatewayEventClient(@Qualifier("gatewayRestClient") RestClient restClient,
                              GatewayTokenProvider tokenProvider,
                              GatewayProperties properties) {
        this.restClient = restClient;
        this.tokenProvider = tokenProvider;
        this.properties = properties;
    }

    /**
     * @param eventName nombre corto del tipo (ViajeIniciado); el namespace se agrega acá.
     * @param jsonPayload los campos de negocio, ya serializados.
     * @throws GatewayRejectedException si el gateway dice que el evento no cumple el contrato:
     *                                  reintentarlo no sirve.
     * @throws GatewayUnavailableException ante cualquier otra falla: conviene reintentar.
     */
    public void publish(String eventName, String jsonPayload) {
        String fqn = properties.namespace() + "." + eventName;
        try {
            send(fqn, jsonPayload);
        } catch (HttpClientErrorException.Unauthorized expired) {
            // El token pudo vencer o rotar entre que lo pedimos y lo usamos: uno nuevo y otra vez.
            tokenProvider.invalidate();
            send(fqn, jsonPayload);
        }
    }

    private void send(String fqn, String jsonPayload) {
        try {
            restClient.post()
                    .uri(properties.baseUrl() + "/api/v1/event-types/{fqn}/events", fqn)
                    .headers(headers -> headers.setBearerAuth(tokenProvider.token()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonPayload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Unauthorized unauthorized) {
            throw unauthorized;
        } catch (HttpClientErrorException clientError) {
            if (clientError.getStatusCode() == HttpStatus.BAD_REQUEST
                    || clientError.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new GatewayRejectedException(fqn + ": " + clientError.getResponseBodyAsString());
            }
            throw new GatewayUnavailableException("El gateway respondió " + clientError.getStatusCode(), clientError);
        } catch (RestClientException unavailable) {
            throw new GatewayUnavailableException("No se pudo publicar " + fqn, unavailable);
        }
    }
}
