package com.citypass.movilidad.exception;

import org.springframework.http.HttpStatus;

/**
 * Dato de entrada inválido que no se puede expresar con Bean Validation, por ejemplo una
 * combinación de campos incoherente. Responde 400 Bad Request.
 *
 * Para reglas por campo usar las anotaciones del DTO: esta excepción es el escape para lo que
 * no entra ahí, no un reemplazo de la validación declarativa.
 */
public class ValidationException extends ApiException {

    public static final String CODE = "VALIDATION_ERROR";

    public ValidationException(String message) {
        super(HttpStatus.BAD_REQUEST, CODE, message);
    }
}
