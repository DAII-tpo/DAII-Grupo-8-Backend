package com.citypass.movilidad.exception;

import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.service.ClimateResponse;
import org.springframework.http.HttpStatus;

/** Rechazo o confirmación requerida por una alerta climática vigente, con la respuesta de la matriz aplicada. */
public class ClimateTripException extends ApiException {
    private final transient ClimateAlert alert;
    private final ClimateResponse response;

    public ClimateTripException(String code, String message, ClimateAlert alert, ClimateResponse response) {
        super(HttpStatus.CONFLICT, code, message);
        this.alert = alert;
        this.response = response;
    }

    public ClimateAlert getAlert() {
        return alert;
    }

    public ClimateResponse getResponse() {
        return response;
    }
}
