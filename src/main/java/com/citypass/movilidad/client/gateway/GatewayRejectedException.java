package com.citypass.movilidad.client.gateway;

/** El gateway rechazó el evento por contrato (400) o tipo inexistente (404): no se reintenta. */
public class GatewayRejectedException extends RuntimeException {

    public GatewayRejectedException(String message) {
        super(message);
    }
}
