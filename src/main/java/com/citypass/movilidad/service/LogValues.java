package com.citypass.movilidad.service;

/** Valores recibidos por eventos o por HTTP que se escriben en el log. */
final class LogValues {
    private LogValues() {
    }

    /** Recorta saltos de línea y tabulaciones para que un valor externo no pueda falsear el log. */
    static String safe(String value) {
        return value == null ? null : value.replaceAll("[\\r\\n\\t]", "_");
    }
}
