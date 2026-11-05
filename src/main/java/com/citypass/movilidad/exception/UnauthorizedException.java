package com.citypass.movilidad.exception;

import org.springframework.http.HttpStatus;

/**
 * Credenciales ausentes, inválidas o vencidas. Responde 401.
 *
 * <p>Deliberadamente <strong>no</strong> extiende {@code AuthenticationException} de Spring
 * Security: {@code GlobalExceptionHandler} relanza esas para que las responda el
 * {@code ExceptionTranslationFilter}, y en los endpoints {@code permitAll} —que es justo donde vive
 * el login— no hay entry point configurado, así que terminaría en un 500. Como {@link ApiException}
 * la mapea el handler que ya existe, con su status y su código.
 */
public class UnauthorizedException extends ApiException {

    public static final String CODE = "UNAUTHORIZED";

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, CODE, message);
    }

    public UnauthorizedException(String code, String message) {
        super(HttpStatus.UNAUTHORIZED, code, message);
    }
}
