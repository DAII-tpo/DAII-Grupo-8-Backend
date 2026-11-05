package com.citypass.movilidad.eventbus.publish;

import com.citypass.movilidad.eventbus.EventBusProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Token de servicio del módulo (client_credentials), cacheado hasta poco antes de vencer.
 * Es el único token con el que se publica: el de una persona nunca se reenvía al bus.
 */
public class ServiceTokenProvider {

    // Se renueva antes de que venza para no mandar un token que expira en vuelo.
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final EventBusProperties properties;
    private final Clock clock;

    private String token;
    private Instant expiresAt = Instant.MIN;

    public ServiceTokenProvider(RestClient restClient, EventBusProperties properties, Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.clock = clock;
    }

    /** Token vigente; pide uno nuevo si no hay o está por vencer. */
    public synchronized String token() {
        if (token == null || !clock.instant().isBefore(expiresAt.minus(EXPIRY_MARGIN))) {
            requestToken();
        }
        return token;
    }

    /** Descarta el token cacheado (por ejemplo, ante un 401: el emisor pudo haber rotado la clave). */
    public synchronized void invalidate() {
        token = null;
        expiresAt = Instant.MIN;
    }

    private void requestToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        TokenResponse response;
        try {
            response = restClient.post()
                    .uri(properties.tokenUrl())
                    .headers(headers -> headers.setBasicAuth(
                            properties.clientId(), properties.clientSecret(), StandardCharsets.UTF_8))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientResponseException ex) {
            // 401 = credenciales inválidas: reintentar no lo arregla, pero la configuración se puede corregir
            // sin perder eventos, así que se informa como reintentable.
            throw new EventGatewayException("El servicio de identidad rechazó el pedido de token: HTTP "
                    + ex.getStatusCode().value(), ex.getStatusCode().value(), true, ex);
        } catch (RestClientException | HttpMessageConversionException ex) {
            throw new EventGatewayException("No se pudo obtener el token de servicio", 0, true, ex);
        }

        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new EventGatewayException("El servicio de identidad devolvió un token vacío", 0, true, null);
        }
        token = response.accessToken();
        expiresAt = clock.instant().plusSeconds(response.expiresIn());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("expires_in") long expiresIn) {
    }
}
