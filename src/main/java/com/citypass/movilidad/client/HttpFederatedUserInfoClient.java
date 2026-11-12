package com.citypass.movilidad.client;

import com.citypass.movilidad.config.IdpProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consulta el perfil al IdP y traduce cualquier falla en un resultado vacío.
 *
 * <p>Que no propague la excepción es deliberado: el perfil es un dato deseable, no un requisito
 * para operar. Si el IdP no contesta, la persona trabaja igual y el perfil se completa en un
 * request posterior.
 */
@Component
public class HttpFederatedUserInfoClient implements FederatedUserInfoClient {

    private static final Logger LOG = LoggerFactory.getLogger(HttpFederatedUserInfoClient.class);

    private final RestClient restClient;
    private final IdpProperties properties;
    private final Clock clock;

    /**
     * Hasta cuándo no reintentar, por persona. Crece con la cantidad de personas cuyo perfil no
     * se pudo traer, que es acotada, y las entradas vencidas se descartan al consultarlas.
     */
    private final Map<String, Instant> retryAfter = new ConcurrentHashMap<>();

    public HttpFederatedUserInfoClient(@Qualifier("idpRestClient") RestClient restClient,
                                       IdpProperties properties, Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Optional<FederatedUserInfo> fetch(String accessToken, String subject) {
        if (!properties.enabled() || isBlank(accessToken) || isBlank(subject) || waiting(subject)) {
            return Optional.empty();
        }
        try {
            MeResponse perfil = restClient.get()
                    .uri(properties.userInfoUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(MeResponse.class);
            if (perfil == null) {
                return registerFailure(subject, "el IdP devolvió un cuerpo vacío");
            }
            retryAfter.remove(subject);
            return Optional.of(toUserInfo(perfil));
        } catch (RestClientException | HttpMessageConversionException ex) {
            return registerFailure(subject, ex.toString());
        }
    }

    /**
     * El IdP devuelve el nombre sin separar, así que se parte en "lo anterior a la última palabra" y
     * "última palabra": es lo que mejor resuelve los dos apellidos, que acá son lo habitual. Con
     * una sola palabra va entera al nombre, para que el nombre nunca quede vacío.
     */
    static FederatedUserInfo toUserInfo(MeResponse perfil) {
        String fullName = perfil.fullName() == null ? "" : perfil.fullName().trim();
        int corte = fullName.lastIndexOf(' ');
        String firstName = corte < 0 ? fullName : fullName.substring(0, corte);
        String lastName = corte < 0 ? null : fullName.substring(corte + 1);
        return new FederatedUserInfo(
                blankToNull(perfil.email()), blankToNull(firstName), blankToNull(lastName));
    }

    /** Vencida la espera, se descarta la entrada para no acumularlas. */
    private boolean waiting(String subject) {
        Instant hasta = retryAfter.get(subject);
        if (hasta == null) {
            return false;
        }
        if (clock.instant().isBefore(hasta)) {
            return true;
        }
        retryAfter.remove(subject);
        return false;
    }

    private Optional<FederatedUserInfo> registerFailure(String subject, String error) {
        retryAfter.put(subject, clock.instant().plus(properties.retryCooldown()));
        LOG.warn("No se pudo traer el perfil del IdP para {}: {}. No se reintenta por {}.",
                subject, error, properties.retryCooldown());
        return Optional.empty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    /** Solo los campos que hacen falta; el IdP devuelve varios más. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MeResponse(String fullName, String email) {
    }
}
