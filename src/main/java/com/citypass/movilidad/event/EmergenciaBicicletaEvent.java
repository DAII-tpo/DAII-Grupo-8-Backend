package com.citypass.movilidad.event;

import com.citypass.movilidad.model.enums.EmergencyPriority;

/**
 * Datos del evento EmergenciaBicicleta (contrato en resources/eventbus/event-types/EmergenciaBicicleta.json).
 * Los nombres de los campos son los del contrato: cambiarlos rompe a Emergencias. Las fechas van en epoch millis.
 */
public record EmergenciaBicicletaEvent(
        String emergenciaId,
        // Identificador estable de la persona (convención del bus): el `sub` del token federado.
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

        /**
         * Igual que en {@code ReporteBicicletaEvent}: el contrato los declara obligatorios y el
         * access token no trae email ni apellido. Acá importa más todavía, porque este evento es
         * el único canal por el que Emergencias se entera del pedido de ayuda: con un nulo el
         * gateway lo rechaza y el aviso no llega a nadie.
         */
        public Usuario {
            nombre = nombre == null ? "" : nombre;
            apellido = apellido == null ? "" : apellido;
            email = email == null ? "" : email;
        }
    }
}
