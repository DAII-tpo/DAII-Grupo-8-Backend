package com.citypass.movilidad.client.gateway;

import com.citypass.movilidad.config.GatewayProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;

/**
 * Token de servicio para el gateway (client_credentials con Basic auth, igual en el
 * Login Federado y en el simulador). Se reutiliza hasta 30 s antes de vencer:
 * los tokens duran 15 minutos y pedir uno por evento sería un login por viaje.
 */
@Component
public class GatewayTokenProvider {

    private static final long RENEW_MARGIN_SECONDS = 30;

    private final RestClient restClient;
    private final GatewayProperties properties;
    private final Clock clock;

    private String token;
    private Instant expiresAt = Instant.EPOCH;

    @Autowired
    public GatewayTokenProvider(@Qualifier("gatewayRestClient") RestClient restClient,
                                GatewayProperties properties) {
        this(restClient, properties, Clock.systemUTC());
    }

    GatewayTokenProvider(RestClient restClient, GatewayProperties properties, Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized String token() {
        if (token == null || clock.instant().isAfter(expiresAt.minusSeconds(RENEW_MARGIN_SECONDS))) {
            refresh();
        }
        return token;
    }

    /** Fuerza un token nuevo: se usa cuando el gateway responde 401 con uno que creíamos vigente. */
    public synchronized void invalidate() {
        token = null;
    }

    private void refresh() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        TokenResponse response = restClient.post()
                .uri(properties.tokenUrl())
                .headers(headers -> headers.setBasicAuth(properties.clientId(), properties.clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new GatewayUnavailableException("El emisor de tokens no devolvió un access_token");
        }

        token = response.accessToken();
        long ttl = response.expiresIn() == null ? 900 : response.expiresIn();
        expiresAt = clock.instant().plusSeconds(ttl);
    }

    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("expires_in") Long expiresIn) {
    }
}
