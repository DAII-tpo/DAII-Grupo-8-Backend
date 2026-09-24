package com.citypass.movilidad.exception;

import org.springframework.http.HttpStatus;

/** El usuario no tiene el rol requerido. Responde 403. */
public class ForbiddenOperationException extends ApiException {

    public static final String CODE = "FORBIDDEN_OPERATION";

    public ForbiddenOperationException(String message) {
        super(HttpStatus.FORBIDDEN, CODE, message);
    }
}
