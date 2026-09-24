package com.citypass.movilidad.exception;

import org.springframework.http.HttpStatus;

/** La operación viola una regla de negocio (ej. estación sin lugar, bici en uso). Responde 409. */
public class BusinessRuleException extends ApiException {

    public static final String CODE = "BUSINESS_RULE_VIOLATION";

    public BusinessRuleException(String message) {
        super(HttpStatus.CONFLICT, CODE, message);
    }
}
