package com.citypass.movilidad.event;

import com.citypass.movilidad.model.enums.EmergencyPriority;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** El payload de EmergenciaBicicleta cumple el contrato que se registra en el bus. */
class EmergenciaBicicletaContractTest {

    private static final Schema SCHEMA = AvroContract.load("EmergenciaBicicleta");

    @Test
    void unEventoConCuestionarioCumpleElContrato() {
        assertThat(AvroContract.violations(SCHEMA, evento(new EmergenciaBicicletaEvent.Triage(
                true, false, false, false, EmergencyPriority.P2), 12.5, "sub-123"))).isEmpty();
    }

    @Test
    void unEventoSinRespuestasNiPrecisionCumpleElContrato() {
        assertThat(AvroContract.violations(SCHEMA, evento(new EmergenciaBicicletaEvent.Triage(
                false, null, null, null, EmergencyPriority.P1), null, null))).isEmpty();
    }

    @Test
    void todasLasPrioridadesExistenEnElContrato() {
        Schema prioridad = SCHEMA.getField("triage").schema().getField("prioridadSugerida").schema();
        for (EmergencyPriority priority : EmergencyPriority.values()) {
            assertThat(prioridad.getEnumSymbols()).contains(priority.name());
        }
    }

    private static EmergenciaBicicletaEvent evento(EmergenciaBicicletaEvent.Triage triage, Double precision,
                                                   String actorSub) {
        return new EmergenciaBicicletaEvent("EMG-20261029-0091", actorSub, 1793300000000L,
                new EmergenciaBicicletaEvent.Ubicacion(-34.6037, -58.3816, precision),
                triage,
                new EmergenciaBicicletaEvent.Viaje(450021L, 1793299280000L, 10L, "11 de Septiembre", 5L, "BIKE-03520"),
                new EmergenciaBicicletaEvent.Usuario(1L, "Ana", "Pérez", "ana.perez@example.com"));
    }
}
