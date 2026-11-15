package com.citypass.movilidad.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Lo que Movilidad usa del evento com.citypass.emergencias.EmergenciaActualizada (Emergencias). La resolución de
 * un pedido de ayuda llega como estadoNuevo RESUELTA; CERRADA es un cambio posterior y no se usa.
 * <p>
 * Las fechas llegan como texto (ISO-8601). Nuestro código EMG-… puede venir en emergenciaId o en correlationId.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmergenciaActualizadaData(
        String emergenciaId,
        String correlationId,
        String estadoAnterior,
        String estadoNuevo,
        String updatedAt,
        String resueltoAt
) {
}
