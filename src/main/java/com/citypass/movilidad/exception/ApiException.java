package com.citypass.movilidad.exception;

import org.springframework.http.HttpStatus;

/**
 * Raíz de las excepciones de dominio de la API (MOV-021).
 *
 * Cada subclase fija el estado HTTP y el código de error con el que responde el
 * GlobalExceptionHandler, para que la traducción excepción -> respuesta viva en un solo lugar
 * y no se repita en cada controller ni en cada handler.
 */
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

    /** Código estable de error, pensado para que el cliente pueda ramificar sin parsear el mensaje. */
    public String getCode() {
        return code;
    }
}
