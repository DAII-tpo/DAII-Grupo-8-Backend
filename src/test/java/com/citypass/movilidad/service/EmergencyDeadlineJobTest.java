package com.citypass.movilidad.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmergencyDeadlineJobTest {

    @Test
    void unaEmergenciaQueFallaNoFrenaALasDemas() {
        EmergencyService service = mock(EmergencyService.class);
        when(service.findExpiredPendingIds()).thenReturn(List.of(1L, 2L));
        when(service.sendIfTriageExpired(1L)).thenThrow(new IllegalStateException("falló"));

        new EmergencyDeadlineJob(service).sendExpired();

        verify(service).sendIfTriageExpired(2L);
    }
}
