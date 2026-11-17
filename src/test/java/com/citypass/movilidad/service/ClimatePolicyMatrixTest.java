package com.citypass.movilidad.service;

import com.citypass.movilidad.model.ClimateAlert.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClimatePolicyMatrixTest {
    private final ClimatePolicyMatrix matrix = new ClimatePolicyMatrix();

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "TORMENTA_ELECTRICA,BAJA,AVISO",
            "TORMENTA_ELECTRICA,MEDIA,BLOQUEO",
            "TORMENTA_ELECTRICA,ALTA,BLOQUEO_Y_AVISO_VIAJES",
            "TORMENTA_ELECTRICA,CRITICA,BLOQUEO_Y_AVISO_VIAJES",
            "HURACAN,BAJA,CONFIRMACION",
            "HURACAN,MEDIA,BLOQUEO",
            "HURACAN,ALTA,BLOQUEO_Y_AVISO_VIAJES",
            "HURACAN,CRITICA,BLOQUEO_Y_AVISO_VIAJES",
            "NIEVE,BAJA,CONFIRMACION",
            "NIEVE,MEDIA,BLOQUEO",
            "NIEVE,ALTA,BLOQUEO_Y_AVISO_VIAJES",
            "NIEVE,CRITICA,BLOQUEO_Y_AVISO_VIAJES",
            "GRANIZO,BAJA,AVISO",
            "GRANIZO,MEDIA,CONFIRMACION",
            "GRANIZO,ALTA,BLOQUEO_Y_AVISO_VIAJES",
            "GRANIZO,CRITICA,BLOQUEO_Y_AVISO_VIAJES",
            "TORMENTA_FUERTE,BAJA,AVISO",
            "TORMENTA_FUERTE,MEDIA,CONFIRMACION",
            "TORMENTA_FUERTE,ALTA,BLOQUEO",
            "TORMENTA_FUERTE,CRITICA,BLOQUEO_Y_AVISO_VIAJES",
            "VIENTO_FUERTE,BAJA,AVISO",
            "VIENTO_FUERTE,MEDIA,CONFIRMACION",
            "VIENTO_FUERTE,ALTA,BLOQUEO",
            "VIENTO_FUERTE,CRITICA,BLOQUEO_Y_AVISO_VIAJES",
            "LLUVIA_INTENSA,BAJA,AVISO",
            "LLUVIA_INTENSA,MEDIA,AVISO",
            "LLUVIA_INTENSA,ALTA,CONFIRMACION",
            "LLUVIA_INTENSA,CRITICA,BLOQUEO_Y_AVISO_VIAJES",
            "OLA_DE_CALOR,BAJA,AVISO",
            "OLA_DE_CALOR,MEDIA,AVISO",
            "OLA_DE_CALOR,ALTA,CONFIRMACION",
            "OLA_DE_CALOR,CRITICA,BLOQUEO_Y_AVISO_VIAJES",
            "OLA_DE_FRIO,BAJA,AVISO",
            "OLA_DE_FRIO,MEDIA,AVISO",
            "OLA_DE_FRIO,ALTA,AVISO",
            "OLA_DE_FRIO,CRITICA,CONFIRMACION",
            "TSUNAMI,BAJA,AVISO",
            "TSUNAMI,MEDIA,CONFIRMACION",
            "TSUNAMI,ALTA,BLOQUEO",
            "TSUNAMI,CRITICA,BLOQUEO_Y_AVISO_VIAJES"
    })
    void respondsAccordingToTheAgreedMatrix(String phenomenon, Severity severity, ClimateResponse expected) {
        assertThat(matrix.responseFor(phenomenon, severity)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingPhenomenonUsesTheBasePolicyWithoutFailing(String phenomenon) {
        assertThat(matrix.responseFor(phenomenon, Severity.ALTA)).isEqualTo(ClimateResponse.BLOQUEO);
    }

    @Test
    void everyKnownPhenomenonHasAFullRow() {
        for (ClimatePhenomenon phenomenon : ClimatePhenomenon.values()) {
            for (Severity severity : Severity.values()) {
                assertThat(matrix.responseFor(phenomenon.name(), severity)).isNotNull();
            }
            assertThat(ClimatePhenomenon.parse(phenomenon.name())).contains(phenomenon);
            assertThat(phenomenon.riskHint()).isNotBlank();
        }
        assertThat(ClimatePhenomenon.parse("desconocido")).isEmpty();
    }

    @Test
    void loggedValuesCannotForgeLogLines() {
        assertThat(LogValues.safe("TSUNAMI\r\n2026 ERROR falso\t")).isEqualTo("TSUNAMI__2026 ERROR falso_");
        assertThat(LogValues.safe("GRANIZO")).isEqualTo("GRANIZO");
        assertThat(LogValues.safe(null)).isNull();
    }

    @Test
    void publishedActionsKeepTheContractStrings() {
        assertThat(ClimateResponse.AVISO.publishedActions()).containsExactly("AVISO_USUARIOS");
        assertThat(ClimateResponse.CONFIRMACION.publishedActions())
                .containsExactly("AVISO_USUARIOS", "CONFIRMACION_RIESGO_AL_ALQUILAR");
        assertThat(ClimateResponse.BLOQUEO.publishedActions())
                .containsExactly("AVISO_USUARIOS", "BLOQUEO_ALQUILER");
        assertThat(ClimateResponse.BLOQUEO_Y_AVISO_VIAJES.publishedActions())
                .containsExactly("AVISO_USUARIOS", "BLOQUEO_ALQUILER", "AVISO_DEVOLUCION_VIAJES_ACTIVOS");
    }

    @Test
    void responsesAreOrderedFromLeastToMostRestrictive() {
        assertThat(List.of(ClimateResponse.values())).containsExactly(ClimateResponse.AVISO,
                ClimateResponse.CONFIRMACION, ClimateResponse.BLOQUEO, ClimateResponse.BLOQUEO_Y_AVISO_VIAJES);
        assertThat(ClimateResponse.AVISO.blocksRental()).isFalse();
        assertThat(ClimateResponse.CONFIRMACION.requiresRiskConfirmation()).isTrue();
        assertThat(ClimateResponse.BLOQUEO.blocksRental()).isTrue();
        assertThat(ClimateResponse.BLOQUEO.notifiesActiveTrips()).isFalse();
        assertThat(ClimateResponse.BLOQUEO_Y_AVISO_VIAJES.blocksRental()).isTrue();
        assertThat(ClimateResponse.BLOQUEO_Y_AVISO_VIAJES.notifiesActiveTrips()).isTrue();
        assertThat(ClimateResponse.AVISO.showsEarlyWarning()).isFalse();
        assertThat(ClimateResponse.CONFIRMACION.showsEarlyWarning()).isTrue();
    }
}
