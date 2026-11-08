package com.citypass.movilidad.exception;

import org.springframework.http.HttpStatus;

/**
 * El email ya tiene una cuenta. Responde 409.
 *
 * <p>Que el registro revele si un email está tomado es una fuga de información aceptada a
 * conciencia: la persona necesita saberlo para entender por qué no puede registrarse. Esconderlo
 * exigiría verificación por mail, que quedó fuera de alcance. El login, en cambio, no distingue.
 */
public class EmailAlreadyRegisteredException extends ApiException {

    public static final String CODE = "EMAIL_ALREADY_REGISTERED";

    public EmailAlreadyRegisteredException() {
        super(HttpStatus.CONFLICT, CODE, "Ya existe una cuenta con ese email");
    }
}
