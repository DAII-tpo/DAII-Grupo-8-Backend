package com.citypass.movilidad.exception;

import com.citypass.movilidad.model.ClimateAlert;
import org.springframework.http.HttpStatus;

/** Rechazo o confirmación requerida por una alerta climática vigente. */
public class ClimateTripException extends ApiException {
    private final ClimateAlert alert;

    public ClimateTripException(String code, String message, ClimateAlert alert) {
        super(HttpStatus.CONFLICT, code, message);
        this.alert = alert;
    }

    public ClimateAlert getAlert() {
        return alert;
    }
}
