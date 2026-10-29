package com.citypass.movilidad.model.enums;

public enum BikeStatus {
    AVAILABLE,
    IN_USE,
    MAINTENANCE,
    OUT_OF_SERVICE,
    STOLEN,
    // Bloqueada por una emergencia durante el viaje: nadie la puede retirar hasta recuperarla.
    BLOCKED
}
