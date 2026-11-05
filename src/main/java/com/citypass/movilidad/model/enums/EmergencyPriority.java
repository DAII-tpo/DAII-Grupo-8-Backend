package com.citypass.movilidad.model.enums;

/** Prioridad sugerida a Emergencias a partir del cuestionario. La asigna Emergencias; esta es orientativa. */
public enum EmergencyPriority {
    // Vehículo involucrado o sangrado (o sin respuestas: se asume lo peor).
    P1,
    // No puede moverse.
    P2,
    // Se mueve, sin sangrado ni vehículo.
    P3,
    // Sin lesiones. El cuestionario actual no la deriva; existe para que Emergencias la pueda usar.
    P4
}
