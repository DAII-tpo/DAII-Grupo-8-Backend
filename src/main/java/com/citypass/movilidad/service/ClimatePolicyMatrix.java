package com.citypass.movilidad.service;

import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.model.ClimateAlert.Severity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

import static com.citypass.movilidad.service.ClimateResponse.AVISO;
import static com.citypass.movilidad.service.ClimateResponse.BLOQUEO;
import static com.citypass.movilidad.service.ClimateResponse.BLOQUEO_Y_AVISO_VIAJES;
import static com.citypass.movilidad.service.ClimateResponse.CONFIRMACION;

/**
 * Política de contingencia climática: respuesta según fenómeno × criticidad. La matriz y sus fundamentos están
 * documentados en docs/adr/ADR-003-politica-contingencia-climatica.md.
 *
 * <p>No se persiste: se calcula desde phenomenon + severity de climate_alerts, así un cambio de matriz aplica
 * también a las alertas vigentes. Un fenómeno desconocido usa la fila base (igual a TORMENTA_FUERTE) y no se
 * rechaza el evento.
 */
@Component
@Slf4j
public class ClimatePolicyMatrix {
    private final Map<ClimatePhenomenon, EnumMap<Severity, ClimateResponse>> matrix =
            new EnumMap<>(ClimatePhenomenon.class);
    private final EnumMap<Severity, ClimateResponse> fallback = row(AVISO, CONFIRMACION, BLOQUEO, BLOQUEO_Y_AVISO_VIAJES);

    public ClimatePolicyMatrix() {
        //                                                  BAJA          MEDIA         ALTA                    CRITICA
        matrix.put(ClimatePhenomenon.TORMENTA_ELECTRICA, row(AVISO,        BLOQUEO,      BLOQUEO_Y_AVISO_VIAJES, BLOQUEO_Y_AVISO_VIAJES));
        matrix.put(ClimatePhenomenon.HURACAN,            row(CONFIRMACION, BLOQUEO,      BLOQUEO_Y_AVISO_VIAJES, BLOQUEO_Y_AVISO_VIAJES));
        matrix.put(ClimatePhenomenon.NIEVE,              row(CONFIRMACION, BLOQUEO,      BLOQUEO_Y_AVISO_VIAJES, BLOQUEO_Y_AVISO_VIAJES));
        matrix.put(ClimatePhenomenon.GRANIZO,            row(AVISO,        CONFIRMACION, BLOQUEO_Y_AVISO_VIAJES, BLOQUEO_Y_AVISO_VIAJES));
        matrix.put(ClimatePhenomenon.TORMENTA_FUERTE,    row(AVISO,        CONFIRMACION, BLOQUEO,                BLOQUEO_Y_AVISO_VIAJES));
        matrix.put(ClimatePhenomenon.VIENTO_FUERTE,      row(AVISO,        CONFIRMACION, BLOQUEO,                BLOQUEO_Y_AVISO_VIAJES));
        matrix.put(ClimatePhenomenon.LLUVIA_INTENSA,     row(AVISO,        AVISO,        CONFIRMACION,           BLOQUEO_Y_AVISO_VIAJES));
        matrix.put(ClimatePhenomenon.OLA_DE_CALOR,       row(AVISO,        AVISO,        CONFIRMACION,           BLOQUEO_Y_AVISO_VIAJES));
        matrix.put(ClimatePhenomenon.OLA_DE_FRIO,        row(AVISO,        AVISO,        AVISO,                  CONFIRMACION));
    }

    public ClimateResponse responseFor(String phenomenon, Severity severity) {
        var row = ClimatePhenomenon.parse(phenomenon).map(matrix::get).orElseGet(() -> {
            log.warn("Fenómeno climático desconocido '{}': se aplica la política base", phenomenon);
            return fallback;
        });
        return row.get(severity);
    }

    public ClimateResponse responseFor(ClimateAlert alert) {
        return responseFor(alert.getPhenomenon(), alert.getSeverity());
    }

    private static EnumMap<Severity, ClimateResponse> row(ClimateResponse low, ClimateResponse medium,
                                                          ClimateResponse high, ClimateResponse critical) {
        EnumMap<Severity, ClimateResponse> row = new EnumMap<>(Severity.class);
        row.put(Severity.BAJA, low);
        row.put(Severity.MEDIA, medium);
        row.put(Severity.ALTA, high);
        row.put(Severity.CRITICA, critical);
        return row;
    }
}
