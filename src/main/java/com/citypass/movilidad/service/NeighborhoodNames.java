package com.citypass.movilidad.service;

import java.text.Normalizer;
import java.util.Locale;

/** Normalización compartida de nombres de barrios recibidos por eventos. */
public final class NeighborhoodNames {
    private NeighborhoodNames() {
    }

    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String withoutAccents = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String normalized = withoutAccents.toUpperCase(Locale.ROOT)
                .replace(".", "")
                .trim()
                .replaceAll("\\s+", " ");
        return switch (normalized) {
            case "BOCA" -> "LA BOCA";
            case "DEVOTO" -> "VILLA DEVOTO";
            case "MATADERO" -> "MATADEROS";
            case "VELEZ SARFIELD" -> "VELEZ SARSFIELD";
            case "VILLA GENERAL MITRE" -> "VILLA GRAL MITRE";
            default -> normalized;
        };
    }
}
