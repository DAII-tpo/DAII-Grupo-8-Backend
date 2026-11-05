package com.citypass.movilidad.event;

import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** El payload de ReporteBicicleta cumple el contrato que se registra en el bus. */
class ReporteBicicletaContractTest {

    private static final Schema SCHEMA = AvroContract.load("ReporteBicicleta");

    @Test
    void unReporteDuranteUnViajeConUbicacionCumpleElContrato() {
        ReporteBicicletaEvent event = evento(
                new ReporteBicicletaEvent.Bicicleta(5L, "BIKE-03520", null, null),
                new ReporteBicicletaEvent.Viaje(450021L, 1793299280000L, 10L, "11 de Septiembre"),
                new ReporteBicicletaEvent.Ubicacion(-34.6037, -58.3816, 15.0),
                "sub-123");

        assertThat(AvroContract.violations(SCHEMA, event)).isEmpty();
    }

    @Test
    void unReporteSinViajeNiUbicacionCumpleElContrato() {
        ReporteBicicletaEvent event = evento(
                new ReporteBicicletaEvent.Bicicleta(5L, "BIKE-03520", 10L, "11 de Septiembre"), null, null, null);

        assertThat(AvroContract.violations(SCHEMA, event)).isEmpty();
    }

    @Test
    void detectaUnPayloadQueNoCumple() {
        ReporteBicicletaEvent event = evento(
                new ReporteBicicletaEvent.Bicicleta(5L, null, null, null), null, null, null);

        assertThat(AvroContract.violations(SCHEMA, event)).containsExactly("data.bicicleta.bicicletaCodigo: se esperaba un texto");
    }

    private static ReporteBicicletaEvent evento(ReporteBicicletaEvent.Bicicleta bicicleta,
                                                ReporteBicicletaEvent.Viaje viaje,
                                                ReporteBicicletaEvent.Ubicacion ubicacion, String actorSub) {
        return new ReporteBicicletaEvent(142L, actorSub, 1793300000000L,
                new ReporteBicicletaEvent.TipoIncidencia("FLAT_TIRE", "Pinchazo"),
                "La rueda trasera está desinflada",
                bicicleta, viaje, ubicacion,
                new ReporteBicicletaEvent.Usuario(1L, "Ana", "Pérez", "ana.perez@example.com"));
    }
}
