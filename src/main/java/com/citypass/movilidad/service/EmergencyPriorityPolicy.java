package com.citypass.movilidad.service;

import com.citypass.movilidad.model.enums.EmergencyPriority;

/** Prioridad sugerida a partir del cuestionario. Ante cualquier duda se asume el caso más grave. */
public final class EmergencyPriorityPolicy {

    private EmergencyPriorityPolicy() {
    }

    public static EmergencyPriority suggest(Boolean canMove, Boolean bleeding, Boolean vehicleInvolved) {
        // Sin respuestas (venció el plazo): no sabemos cómo está, así que se trata como lo peor.
        if (canMove == null || bleeding == null || vehicleInvolved == null) {
            return EmergencyPriority.P1;
        }
        if (bleeding || vehicleInvolved) {
            return EmergencyPriority.P1;
        }
        return canMove ? EmergencyPriority.P3 : EmergencyPriority.P2;
    }
}
