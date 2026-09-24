package com.citypass.movilidad.exception;

import org.springframework.http.HttpStatus;

/** El recurso no existe o fue dado de baja. Responde 404. */
public class ResourceNotFoundException extends ApiException {

    public static final String CODE = "RESOURCE_NOT_FOUND";

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, CODE, message);
    }
}
