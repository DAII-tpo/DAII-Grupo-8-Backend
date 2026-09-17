package com.citypass.movilidad.exception.station;

import com.citypass.movilidad.exception.ResourceNotFoundException;

/** Caso concreto de recurso inexistente: hereda el 404 y el formato de error comunes. */
public class StationNotFoundException extends ResourceNotFoundException {

    public static final String CODE = "STATION_NOT_FOUND";

    public StationNotFoundException(Long stationId) {
        super(CODE, "La estación con ID %s no existe".formatted(stationId));
    }
}
