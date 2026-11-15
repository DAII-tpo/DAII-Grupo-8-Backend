package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.config.EmergencyProperties;
import com.citypass.movilidad.eventbus.EventMetadata;
import com.citypass.movilidad.service.EmergencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmergenciaResueltaHandlerTest {

    private static final String TOPIC = "com.citypass.emergencias.EmergenciaActualizada";
    private static final Instant RECEIVED = Instant.parse("2026-11-15T13:00:30Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EmergencyService emergencyService;
    private EmergenciaResueltaHandler handler;

    @BeforeEach
    void setUp() {
        emergencyService = mock(EmergencyService.class);
        handler = new EmergenciaResueltaHandler(emergencyService, objectMapper,
                new EmergencyProperties(Duration.ofSeconds(10), Duration.ofSeconds(60), TOPIC));
    }

    @Test
    void escuchaElTopicoConfigurado() {
        assertThat(handler.topic()).isEqualTo(TOPIC);
    }

    @Test
    void conRESUELTAPideCerrarElViajeConLaFechaDeResolucion() {
        when(emergencyService.resolveByEmergencias("EMG-20261029-0001", Instant.parse("2026-11-15T13:00:00Z")))
                .thenReturn(true);

        handler.handle(event("EMG-20261029-0001", "corr-1", " resuelta ", "2026-11-15T13:00:00Z"));

        verify(emergencyService).resolveByEmergencias("EMG-20261029-0001", Instant.parse("2026-11-15T13:00:00Z"));
        verify(emergencyService, never()).resolveByEmergencias("corr-1", Instant.parse("2026-11-15T13:00:00Z"));
    }

    @Test
    void siElEmergenciaIdNoEsNuestroPruebaConElCorrelationId() {
        handler.handle(event("8f3c-emergencias", "EMG-20261029-0001", "RESUELTA", null));

        // Sin resueltoAt ni updatedAt se usa cuándo lo recibió el bus.
        verify(emergencyService).resolveByEmergencias("8f3c-emergencias", RECEIVED);
        verify(emergencyService).resolveByEmergencias("EMG-20261029-0001", RECEIVED);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"EN_CURSO", "CERRADA", "DESPACHADA", "null"})
    void ignoraLosDemasEstados(String estadoNuevo) {
        handler.handle(event("EMG-20261029-0001", "corr-1", estadoNuevo, "2026-11-15T13:00:00Z"));

        verify(emergencyService, never()).resolveByEmergencias(any(), any());
    }

    @Test
    void unPayloadIlegibleNoSeReintenta() {
        ObjectNode data = objectMapper.createObjectNode();
        data.putArray("estadoNuevo");
        IncomingEvent event = new IncomingEvent(TOPIC, metadata(), data);

        assertThatThrownBy(() -> handler.handle(event)).isInstanceOf(InvalidEventException.class);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "2026-11-15T13:00:00Z,          2026-11-15T13:00:00Z",
            "2026-11-15T10:00:00-03:00,     2026-11-15T13:00:00Z",
            "2026-11-15T13:00:00,           2026-11-15T13:00:00Z",
            "1794747600000,                 2026-11-15T13:00:00Z",
            "no-es-fecha,                   null",
            "null,                          null"
    })
    void aceptaLosFormatosDeFechaHabituales(String value, String expected) {
        assertThat(EmergenciaResueltaHandler.parseInstant(value))
                .isEqualTo(expected == null ? null : Instant.parse(expected));
    }

    private IncomingEvent event(String emergenciaId, String correlationId, String estadoNuevo, String resueltoAt) {
        ObjectNode data = objectMapper.createObjectNode()
                .put("emergenciaId", emergenciaId)
                .put("correlationId", correlationId)
                .put("estadoNuevo", estadoNuevo)
                .put("resueltoAt", resueltoAt)
                .put("barrio", "San Nicolás");
        return new IncomingEvent(TOPIC, metadata(), data);
    }

    private static EventMetadata metadata() {
        return new EventMetadata("e-1", TOPIC, RECEIVED, "grupo5", "tok", 80, "hash", "0.0.1", "gw");
    }
}
