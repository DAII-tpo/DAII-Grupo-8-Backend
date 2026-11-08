package com.citypass.movilidad.event;

/**
 * Datos del evento ReporteBicicleta (contrato en resources/eventbus/event-types/ReporteBicicleta.json): un
 * problema con una bicicleta reportado por un usuario. Los nombres de los campos son los del contrato.
 * Las fechas van en epoch millis.
 */
public record ReporteBicicletaEvent(
        long reporteId,
        // Identificador estable de la persona (convención del bus): el `sub` del token federado.
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

        /**
         * El contrato declara los tres campos como obligatorios, pero el access token no trae
         * email ni apellido: si el IdP no los pudo dar, la fila local queda incompleta. Un nulo
         * haría que el gateway rechazara el evento y terminara en FAILED sin que nadie lo note,
         * así que se normaliza a vacío. Es a propósito que no se invente una dirección con forma
         * de mail real: un dato falso es peor que un campo vacío.
         */
        public Usuario {
            nombre = nombre == null ? "" : nombre;
            apellido = apellido == null ? "" : apellido;
            email = email == null ? "" : email;
        }
    }
}
