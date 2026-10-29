package com.citypass.movilidad.dto.event;

/**
 * Payload de com.citypass.movilidad.EmergenciaSolicitada.
 * Enviado a EDA y consumido por Emergencias y Seguridad (Grupo 6) para despacho prioritario.
 */
public record EmergenciaSolicitadaData(
        String reclamoId,
        String correlationId,
        String origen,
        String tipoEmergencia,
        String prioridad,
        String descripcion,
        String direccion,
        double latitud,
        double longitud,
        long solicitadoAt
) {
}
