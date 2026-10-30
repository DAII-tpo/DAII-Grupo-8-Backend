package com.citypass.movilidad.dto.event;

import java.util.List;

/**
 * Payload de com.citypass.movilidad.MovilidadObstruccionEvaluada.
 * Enviado a EDA y consumido por Espacios Públicos y Cultura (Grupo 7).
 */
public record MovilidadObstruccionEvaluadaData(
        String reclamoId,
        String correlationId,
        double latitud,
        double longitud,
        String direccion,
        String barrio,
        boolean afectaCiclovia,
        List<Long> estacionesAfectadasIds,
        String impactoCirculacion,
        String desvioSugerido,
        boolean requiereIntervencionInmediata,
        long evaluadoAt
) {
}

