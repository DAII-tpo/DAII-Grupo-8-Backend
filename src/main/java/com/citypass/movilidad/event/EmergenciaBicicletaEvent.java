package com.citypass.movilidad.event;

import com.citypass.movilidad.model.enums.EmergencyPriority;

/**
 * Datos del evento EmergenciaBicicleta (contrato en resources/eventbus/event-types/EmergenciaBicicleta.json).
 * Los nombres de los campos son los del contrato: cambiarlos rompe a Emergencias. Las fechas van en epoch millis.
 */
public record EmergenciaBicicletaEvent(
        String emergenciaId,
        // Identificador estable de la persona (convención del bus). Null hasta integrar el Login Federado.
        String actorSub,
        long reportadaEn,
        Ubicacion ubicacion,
        Triage triage,
        Viaje viaje,
        Usuario usuario) {

    /** Ubicación del usuario al pedir ayuda, tomada del navegador. */
    public record Ubicacion(double latitud, double longitud, Double precisionMetros) {
    }

    /** Respuestas del cuestionario; null si venció el plazo sin respuesta. */
    public record Triage(boolean respondido, Boolean puedeMoverse, Boolean estaSangrando,
                         Boolean vehiculoInvolucrado, EmergencyPriority prioridadSugerida) {
    }

    public record Viaje(long viajeId, long iniciadoEn, long estacionOrigenId, String estacionOrigenNombre,
                        long bicicletaId, String bicicletaCodigo) {
    }

    public record Usuario(long usuarioId, String nombre, String apellido, String email) {
    }
}
