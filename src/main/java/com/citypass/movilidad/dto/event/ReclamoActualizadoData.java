package com.citypass.movilidad.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload de negocio del evento com.citypass.reclamos.ReclamoActualizado (Grupo 5).
 * Cuando estadoNuevo es RESUELTO, la obstrucción reportada fue liberada.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReclamoActualizadoData(
        String reclamoId,
        String ciudadanoId,
        String estadoAnterior,
        String estadoNuevo,
        String motivo,
        String asignadoA,
        String cambiadoPor,
        String correlationId,
        Long updatedAt
) {
}

