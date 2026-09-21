package com.citypass.movilidad.client;

/**
 * El servicio de recomendación no pudo dar una respuesta utilizable: no respondió, tardó de más,
 * devolvió un error o un cuerpo que no se entiende.
 *
 * A propósito <strong>no</strong> extiende ApiException: nunca tiene que llegar al cliente como
 * error HTTP. Es una señal interna para que StationRecommendationService use el criterio de
 * respaldo y el módulo de Movilidad siga respondiendo.
 */
public class RecommendationUnavailableException extends RuntimeException {

    public RecommendationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public RecommendationUnavailableException(String message) {
        super(message, null);
    }
}
