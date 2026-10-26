package com.citypass.movilidad.eventbus.publish;

/**
 * Falla al hablar con el gateway o con el servicio de identidad.
 * {@code retryable} indica si tiene sentido volver a intentar el mismo pedido más tarde.
 */
public class EventGatewayException extends RuntimeException {

    // 0 cuando no hubo respuesta HTTP (timeout, conexión rechazada, DNS).
    private final int status;
    private final boolean retryable;

    public EventGatewayException(String message, int status, boolean retryable, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.retryable = retryable;
    }

    public int status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }
}
