package com.citypass.movilidad.exception;

import org.springframework.http.HttpStatus;

/** Dato de entrada inválido que no se puede validar con anotaciones. Responde 400. */
public class ValidationException extends ApiException {

    public static final String CODE = "VALIDATION_ERROR";

    public ValidationException(String message) {
        super(HttpStatus.BAD_REQUEST, CODE, message);
    }
}
