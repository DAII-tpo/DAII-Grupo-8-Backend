package com.citypass.movilidad.service;

import com.citypass.movilidad.model.enums.EmergencyPriority;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class EmergencyPriorityPolicyTest {

    @ParameterizedTest(name = "puedeMoverse={0}, sangra={1}, vehículo={2} → {3}")
    @CsvSource(nullValues = "null", value = {
            "true,  false, true,  P1",
            "true,  true,  false, P1",
            "false, true,  true,  P1",
            "false, false, false, P2",
            "true,  false, false, P3",
            // Sin respuestas se asume el peor caso.
            "null,  null,  null,  P1",
            "true,  null,  false, P1"
    })
    void sugierePrioridadSegunElCuestionario(Boolean canMove, Boolean bleeding, Boolean vehicle,
                                             EmergencyPriority expected) {
        assertThat(EmergencyPriorityPolicy.suggest(canMove, bleeding, vehicle)).isEqualTo(expected);
    }
}
