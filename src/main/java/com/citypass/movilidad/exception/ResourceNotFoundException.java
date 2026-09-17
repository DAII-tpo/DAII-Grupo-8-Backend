package com.citypass.movilidad.exception;

import org.springframework.http.HttpStatus;

/** El recurso solicitado no existe o fue dado de baja. Responde 404 Not Found. */
public class ResourceNotFoundException extends ApiException {

    public static final String CODE = "RESOURCE_NOT_FOUND";

    public ResourceNotFoundException(String message) {
        this(CODE, message);
    }

    protected ResourceNotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
