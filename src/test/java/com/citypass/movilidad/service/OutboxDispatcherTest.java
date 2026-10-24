package com.citypass.movilidad.service;

import com.citypass.movilidad.client.gateway.GatewayEventClient;
import com.citypass.movilidad.client.gateway.GatewayRejectedException;
import com.citypass.movilidad.client.gateway.GatewayUnavailableException;
import com.citypass.movilidad.model.EventOutbox;
import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.model.enums.EventOutboxStatus;
import com.citypass.movilidad.repository.EventOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxDispatcherTest {

    private EventOutboxRepository outboxRepository;
    private GatewayEventClient gatewayClient;
    private OutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(EventOutboxRepository.class);
        gatewayClient = mock(GatewayEventClient.class);
        // maxAttempts = 3
        dispatcher = new OutboxDispatcher(outboxRepository, gatewayClient, IncidentProximityFilterTest.properties(300));
    }

    @Test
    void publicaLosPendientesYLosMarcaComoPublicados() {
        EventOutbox event = pending("ViajeIniciado", "{\"id\":\"1\"}");
        when(outboxRepository.findTop50ByStatusOrderByCreatedAtAsc(EventOutboxStatus.PENDING)).thenReturn(List.of(event));

        assertThat(dispatcher.dispatch()).isEqualTo(1);
        verify(gatewayClient).publish("ViajeIniciado", "{\"id\":\"1\"}");
        assertThat(event.getStatus()).isEqualTo(EventOutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    void unRechazoPorContratoPasaAFailedSinReintentos() {
        EventOutbox event = pending("ViajeIniciado", "{}");
        when(outboxRepository.findTop50ByStatusOrderByCreatedAtAsc(EventOutboxStatus.PENDING)).thenReturn(List.of(event));
        doThrow(new GatewayRejectedException("falta id")).when(gatewayClient).publish("ViajeIniciado", "{}");

        dispatcher.dispatch();

        assertThat(event.getStatus()).isEqualTo(EventOutboxStatus.FAILED);
        assertThat(event.getLastError()).contains("falta id");
    }

    @Test
    void conElGatewayCaidoCortaElLoteYDejaElEventoPendiente() {
        EventOutbox first = pending("ViajeIniciado", "{\"id\":\"1\"}");
        EventOutbox second = pending("ViajeTerminado", "{\"id\":\"1\"}");
        when(outboxRepository.findTop50ByStatusOrderByCreatedAtAsc(EventOutboxStatus.PENDING)).thenReturn(List.of(first, second));
        doThrow(new GatewayUnavailableException("timeout")).when(gatewayClient).publish("ViajeIniciado", "{\"id\":\"1\"}");

        dispatcher.dispatch();

        assertThat(first.getStatus()).isEqualTo(EventOutboxStatus.PENDING);
        assertThat(first.getAttempts()).isEqualTo(1);
        verify(gatewayClient, never()).publish("ViajeTerminado", "{\"id\":\"1\"}");
    }

    @Test
    void alAgotarLosIntentosElEventoPasaAFailed() {
        EventOutbox event = pending("ViajeIniciado", "{}");
        event.setAttempts(2);
        when(outboxRepository.findTop50ByStatusOrderByCreatedAtAsc(EventOutboxStatus.PENDING)).thenReturn(List.of(event));
        doThrow(new GatewayUnavailableException("timeout")).when(gatewayClient).publish("ViajeIniciado", "{}");

        dispatcher.dispatch();

        assertThat(event.getStatus()).isEqualTo(EventOutboxStatus.FAILED);
    }

    private static EventOutbox pending(String type, String payload) {
        return EventOutbox.builder().aggregateType(AggregateType.TRIP).aggregateId("1")
                .eventType(type).payload(payload).build();
    }
}
