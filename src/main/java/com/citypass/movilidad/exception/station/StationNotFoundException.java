package com.citypass.movilidad.exception.station;

import com.citypass.movilidad.exception.ApiException;

import org.springframework.http.HttpStatus;

/**
 * Estación inexistente. Cuelga directamente de ApiException, igual que
 * {@link com.citypass.movilidad.exception.ResourceNotFoundException}, en vez de heredar de
 * ella: responde el mismo 404 con el formato común y mantiene la jerarquía chata.
 */
public class StationNotFoundException extends ApiException {

    public static final String CODE = "STATION_NOT_FOUND";

    public StationNotFoundException(Long stationId) {
        super(HttpStatus.NOT_FOUND, CODE, "La estación con ID %s no existe".formatted(stationId));
    }
}
