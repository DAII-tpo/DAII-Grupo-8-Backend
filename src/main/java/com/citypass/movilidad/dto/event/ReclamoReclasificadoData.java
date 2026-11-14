package com.citypass.movilidad.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Payload de negocio de com.citypass.reclamos.ReclamoReclasificado. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReclamoReclasificadoData(
        String reclamoId,
        String ciudadanoId,
        String categoriaAnterior,
        String categoriaNueva,
        String prioridadAnterior,
        String prioridadNueva,
        String objetoAfectadoAnterior,
        String objetoAfectadoNuevo,
        boolean afectaCirculacionAnterior,
        boolean afectaCirculacionNueva,
        String titulo,
        String descripcion,
        String barrio,
        Double latitud,
        Double longitud,
        String reclasificadoPor,
        String correlationId,
        Long reclasificadoAt
) {
}
