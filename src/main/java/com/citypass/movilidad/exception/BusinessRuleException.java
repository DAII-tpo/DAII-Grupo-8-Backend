package com.citypass.movilidad.exception;

import org.springframework.http.HttpStatus;

/**
 * La solicitud es sintácticamente válida pero choca con una regla de negocio o con el estado
 * actual del recurso (bicicleta en uso, estación sin capacidad, viaje ya finalizado).
 * Responde 409 Conflict.
 */
public class BusinessRuleException extends ApiException {

    public static final String CODE = "BUSINESS_RULE_VIOLATION";

    public BusinessRuleException(String message) {
        this(CODE, message);
    }

    protected BusinessRuleException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
