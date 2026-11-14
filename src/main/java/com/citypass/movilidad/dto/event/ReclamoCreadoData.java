package com.citypass.movilidad.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload de negocio de com.citypass.reclamos.ReclamoCreado.v3 (Grupo 5).
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
        String estado,
        String origenClasificacion,
        Double confianzaClasificacion,
        String direccion,
        String barrio,
        Double latitud,
        Double longitud,
        String objetoAfectado,
        Boolean afectaCirculacion,
        String correlationId,
        Long createdAt,
        String alertaId,
        String causaId,
        String causaTipo,
        String actividadId
) {
    /** Constructor de compatibilidad para la forma local previa del DTO. */
    public ReclamoCreadoData(String reclamoId, String ciudadanoId, String canal, String titulo,
                             String descripcion, String categoria, String prioridad, String estado,
                             String origenClasificacion, Double confianzaClasificacion, String direccion,
                             String barrio, Double latitud, Double longitud, String objetoAfectado,
                             Boolean afectaCirculacion, String correlationId, Long createdAt) {
        this(reclamoId, ciudadanoId, canal, titulo, descripcion, categoria, prioridad, estado,
                origenClasificacion, confianzaClasificacion, direccion, barrio, latitud, longitud,
                objetoAfectado, afectaCirculacion, correlationId, createdAt, null, null, null, null);
    }
}

