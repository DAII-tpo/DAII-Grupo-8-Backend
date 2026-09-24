package com.citypass.movilidad.exception.station;

import com.citypass.movilidad.exception.ApiException;

import org.springframework.http.HttpStatus;

/** La estación no existe. Responde 404. */
public class StationNotFoundException extends ApiException {

    public static final String CODE = "STATION_NOT_FOUND";

    public StationNotFoundException(Long stationId) {
        super(HttpStatus.NOT_FOUND, CODE, "La estación con ID %s no existe".formatted(stationId));
    }
}
