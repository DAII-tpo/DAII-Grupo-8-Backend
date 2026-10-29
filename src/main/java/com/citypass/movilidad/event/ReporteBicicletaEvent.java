package com.citypass.movilidad.event;

/**
 * Datos del evento ReporteBicicleta (contrato en resources/eventbus/event-types/ReporteBicicleta.json): un
 * problema con una bicicleta reportado por un usuario. Los nombres de los campos son los del contrato.
 * Las fechas van en epoch millis.
 */
public record ReporteBicicletaEvent(
        long reporteId,
        // Identificador estable de la persona (convención del bus). Null hasta integrar el Login Federado.
        String actorSub,
        long reportadoEn,
        TipoIncidencia tipo,
        String descripcion,
        Bicicleta bicicleta,
        // Solo si se reportó durante un viaje con esa bicicleta.
        Viaje viaje,
        // Solo si el navegador compartió la ubicación.
        Ubicacion ubicacion,
        Usuario usuario) {

    public record TipoIncidencia(String codigo, String nombre) {
    }

    /** Estación donde estaba la bici al reportar; null si estaba en un viaje. */
    public record Bicicleta(long bicicletaId, String bicicletaCodigo, Long estacionId, String estacionNombre) {
    }

    public record Viaje(long viajeId, long iniciadoEn, long estacionOrigenId, String estacionOrigenNombre) {
    }

    public record Ubicacion(double latitud, double longitud, Double precisionMetros) {
    }

    public record Usuario(long usuarioId, String nombre, String apellido, String email) {
    }
}
