package com.citypass.movilidad.client;

/**
 * El servicio de recomendación no dio una respuesta utilizable. Señal interna para usar el respaldo:
 * no extiende ApiException porque nunca llega al cliente.
 */
public class RecommendationUnavailableException extends RuntimeException {

    public RecommendationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public RecommendationUnavailableException(String message) {
        super(message, null);
    }
}
