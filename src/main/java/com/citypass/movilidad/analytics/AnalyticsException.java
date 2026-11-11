package com.citypass.movilidad.analytics;

import com.citypass.movilidad.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Nunca incluye cuerpos de terceros, credenciales ni JWT en mensajes o causas. */
public class AnalyticsException extends ApiException {
    public AnalyticsException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
