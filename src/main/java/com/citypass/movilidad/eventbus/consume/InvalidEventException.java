package com.citypass.movilidad.eventbus.consume;

/** El mensaje no tiene la forma de un evento del bus (envelope metadata/data). No se reintenta. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
