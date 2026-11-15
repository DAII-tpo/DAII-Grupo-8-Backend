package com.citypass.movilidad.model.enums;

public enum EmergencyStatus {
    // Se puede cancelar o se está esperando el cuestionario.
    PENDING,
    CANCELLED,
    // Se publicó el evento EmergenciaBicicleta.
    REPORTED,
    // Emergencias informó que la resolvió (EmergenciaActualizada con estadoNuevo RESUELTA).
    RESOLVED
}
