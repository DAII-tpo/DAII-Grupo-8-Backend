package com.citypass.movilidad.model.enums;

public enum TripStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED,
    INCIDENT,
    // Pausado por un pedido de ayuda (SOS): no se puede finalizar ni iniciar otro hasta que se resuelva.
    EMERGENCY
}
