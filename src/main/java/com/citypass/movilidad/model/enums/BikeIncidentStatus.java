package com.citypass.movilidad.model.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum BikeIncidentStatus {
    OPEN,
    UNDER_REVIEW,
    RESOLVED,
    REJECTED;

    /** Incidencias que todavía no se cerraron: la bicicleta no debe volver a circular. */
    public static final Set<BikeIncidentStatus> PENDING =
            Collections.unmodifiableSet(EnumSet.of(OPEN, UNDER_REVIEW));
}
