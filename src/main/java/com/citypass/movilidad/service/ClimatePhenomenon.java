package com.citypass.movilidad.service;

import java.util.Arrays;
import java.util.Optional;

/** Fenómenos que informa Espacios Públicos en CriticidadClimaticaConfirmada. */
public enum ClimatePhenomenon {
    TORMENTA_ELECTRICA("riesgo de rayos"),
    HURACAN("vientos extremos"),
    NIEVE("calzada con nieve o hielo"),
    GRANIZO("caída de granizo"),
    TORMENTA_FUERTE("tormenta fuerte"),
    VIENTO_FUERTE("ráfagas de viento"),
    LLUVIA_INTENSA("calzada resbaladiza"),
    OLA_DE_CALOR("calor extremo"),
    OLA_DE_FRIO("frío extremo");

    private final String riskHint;

    ClimatePhenomenon(String riskHint) {
        this.riskHint = riskHint;
    }

    /** Texto corto para los mensajes al usuario ("riesgo de rayos", "calor extremo"...). */
    public String riskHint() {
        return riskHint;
    }

    /** Fenómeno conocido o vacío; nunca lanza excepción, para no rechazar eventos con fenómenos nuevos. */
    public static Optional<ClimatePhenomenon> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim();
        return Arrays.stream(values()).filter(phenomenon -> phenomenon.name().equals(normalized)).findFirst();
    }
}
