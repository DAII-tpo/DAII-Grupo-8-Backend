package com.citypass.movilidad.model.enums;

public enum TripStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED,
    INCIDENT,
    // Pausado por un pedido de ayuda (SOS): no se puede finalizar ni iniciar otro hasta que se resuelva.
    EMERGENCY,
    // Cerrado sin cargo porque Emergencias resolvió el pedido de ayuda: la bici quedó en la estación más cercana.
    CLOSED_BY_EMERGENCY
}
