package com.citypass.movilidad.exception;

import org.springframework.http.HttpStatus;

/** El usuario existe pero no tiene el rol requerido para la operación. */
public class ForbiddenOperationException extends ApiException {

    public static final String CODE = "FORBIDDEN_OPERATION";

    public ForbiddenOperationException(String message) {
        super(HttpStatus.FORBIDDEN, CODE, message);
    }
}
