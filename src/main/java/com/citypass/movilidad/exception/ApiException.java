package com.citypass.movilidad.exception;

import org.springframework.http.HttpStatus;

/** Base de las excepciones de dominio: cada subclase define su estado HTTP y su código de error. */
public abstract class ApiException extends RuntimeException {

    private final transient HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** Código de error estable, para que el cliente no dependa del mensaje. */
    public String getCode() {
        return code;
    }
}
