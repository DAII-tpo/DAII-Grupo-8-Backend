package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.eventbus.EventMetadata;
import com.citypass.movilidad.service.ClimateAlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ClimateAlertHandlersTest {
    private final ClimateAlertService alerts = mock(ClimateAlertService.class);
    private final ClimateAlertHandlers handlers = new ClimateAlertHandlers();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void confirmedEventPreservesPayloadAndGatewayTimestamp() throws Exception {
        var handler = handlers.climateConfirmed(alerts);
        var payload = mapper.readTree("{\"alertaId\":\"alerta-1\"}");
        Instant receivedAt = Instant.parse("2026-11-14T12:00:00.123Z");
        var metadata = new EventMetadata("confirmada-1", handler.topic(), receivedAt,
                "espacios", null, null, null, null, null);

        handler.handle(new IncomingEvent(handler.topic(), metadata, payload));

        assertThat(handler.topic()).isEqualTo("com.citypass.espacios.CriticidadClimaticaConfirmada");
        verify(alerts).confirm(payload, receivedAt);
    }

    @Test
    void finishedEventIsDeliveredToTheFinalizationFlow() throws Exception {
        var handler = handlers.climateFinished(alerts);
        var payload = mapper.readTree("{\"alertaId\":\"alerta-1\",\"finalizadaEn\":1794657600000}");

        handler.handle(new IncomingEvent(handler.topic(), null, payload));

        assertThat(handler.topic()).isEqualTo("com.citypass.espacios.CriticidadClimaticaFinalizada");
        verify(alerts).finish(payload);
    }
}
