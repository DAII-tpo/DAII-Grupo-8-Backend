package com.citypass.movilidad.exception.station;

public class StationNotFoundException extends RuntimeException {

    public StationNotFoundException(Long stationId) {
        super("La estación con ID %s no existe".formatted(stationId));
    }
}
