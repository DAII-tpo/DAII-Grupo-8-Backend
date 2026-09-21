package com.citypass.movilidad.client;

import com.citypass.movilidad.client.dto.RecommendationApiRequest;
import com.citypass.movilidad.client.dto.RecommendationApiResponse;
import com.citypass.movilidad.config.RecommendationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Acceso HTTP al servicio de recomendación de estaciones (MOV-041, ver ADR-001).
 *
 * Su única responsabilidad es hablar con el servicio y traducir cualquier forma de falla a
 * RecommendationUnavailableException, para que quien lo use tenga un solo caso de error que
 * manejar en vez de distinguir entre timeouts, 5xx y cuerpos ilegibles.
 */
@Component
public class RecommendationClient {

    private static final Logger LOG = LoggerFactory.getLogger(RecommendationClient.class);

    private static final String RECOMMENDATIONS_PATH = "/v1/recommendations";
    private static final String HEALTH_PATH = "/health";

    private final RestClient restClient;
    private final boolean warmUpEnabled;

    /** Evita acumular pings cuando llegan muchas consultas mientras el servicio está dormido. */
    private final AtomicBoolean warmUpInProgress = new AtomicBoolean(false);

    public RecommendationClient(@Qualifier("recommendationRestClient") RestClient restClient,
                                RecommendationProperties properties) {
        this.restClient = restClient;
        this.warmUpEnabled = properties.warmUpEnabled();
    }

    /**
     * @throws RecommendationUnavailableException ante cualquier falla de transporte, error HTTP,
     *                                            cuerpo vacío o respuesta que no se puede leer
     */
    public RecommendationApiResponse recommend(RecommendationApiRequest request) {
        RecommendationApiResponse response;
        try {
            response = restClient.post()
                    .uri(RECOMMENDATIONS_PATH)
                    .body(request)
                    .retrieve()
                    .body(RecommendationApiResponse.class);
        } catch (RestClientException | HttpMessageConversionException ex) {
            throw new RecommendationUnavailableException(
                    "El servicio de recomendación no respondió correctamente", ex);
        }

        // Un 200 con cuerpo vacío o sin status es tan inservible como un error: se trata igual.
        if (response == null || response.status() == null) {
            throw new RecommendationUnavailableException(
                    "El servicio de recomendación devolvió una respuesta vacía");
        }
        return response;
    }

    /** Despierta el servicio al arrancar el backend, para que el primer usuario no pague la espera. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpOnStartup() {
        warmUp();
    }

    /**
     * Ping a /health sin bloquear ni propagar errores. En el plan free de Render el servicio se
     * duerme sin tráfico: despertarlo después de un respaldo hace que la próxima consulta sí
     * llegue al modelo.
     */
    public void warmUp() {
        if (!warmUpEnabled || !warmUpInProgress.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                restClient.get().uri(HEALTH_PATH).retrieve().toBodilessEntity();
                LOG.debug("Servicio de recomendación disponible");
            } catch (RestClientException ex) {
                LOG.debug("El servicio de recomendación sigue sin responder al ping: {}", ex.getMessage());
            } finally {
                warmUpInProgress.set(false);
            }
        });
    }
}
