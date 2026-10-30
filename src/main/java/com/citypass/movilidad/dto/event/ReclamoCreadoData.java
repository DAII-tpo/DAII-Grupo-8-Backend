package com.citypass.movilidad.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload de negocio del evento com.citypass.reclamos.ReclamoCreado (Grupo 5).
 * Tolera campos adicionales o futuros según el contrato del bus.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReclamoCreadoData(
        String reclamoId,
        String ciudadanoId,
        String canal,
        String titulo,
        String descripcion,
        String categoria,
        String prioridad,
        String origenClasificacion,
        Double confianzaClasificacion,
        String direccion,
        String barrio,
        Double latitud,
        Double longitud,
        Boolean afectaCirculacion,
        String correlationId,
        Long createdAt
) {
}

