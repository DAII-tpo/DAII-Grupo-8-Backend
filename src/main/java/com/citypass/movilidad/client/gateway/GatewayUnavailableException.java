package com.citypass.movilidad.client.gateway;

/** El gateway o el emisor de tokens no respondieron: el evento se reintenta más tarde. */
public class GatewayUnavailableException extends RuntimeException {

    public GatewayUnavailableException(String message) {
        super(message);
    }

    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
