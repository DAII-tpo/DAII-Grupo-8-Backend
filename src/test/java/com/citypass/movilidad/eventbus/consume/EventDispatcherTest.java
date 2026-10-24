package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.model.EventInbox;
import com.citypass.movilidad.model.enums.EventInboxStatus;
import com.citypass.movilidad.repository.EventInboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventDispatcherTest {

    private static final String TOPIC = "com.citypass.test.EventoDePrueba";
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    private EventHandler handler;
    private EventInboxRepository inboxRepository;
    private EventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        handler = mock(EventHandler.class);
        when(handler.topic()).thenReturn(TOPIC);
        inboxRepository = mock(EventInboxRepository.class);
        dispatcher = new EventDispatcher(List.of(handler), new EventEnvelopeMapper(new ObjectMapper()),
                inboxRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void entregaElEventoAlHandlerDeSuTopicoYLoRegistraComoProcesado() {
        dispatcher.dispatch(consumerRecord("e-1"));

        ArgumentCaptor<IncomingEvent> event = ArgumentCaptor.forClass(IncomingEvent.class);
        verify(handler).handle(event.capture());
        assertThat(event.getValue().metadata().eventId()).isEqualTo("e-1");

        ArgumentCaptor<EventInbox> inbox = ArgumentCaptor.forClass(EventInbox.class);
        verify(inboxRepository).save(inbox.capture());
        assertThat(inbox.getValue().getEventId()).isEqualTo("e-1");
        assertThat(inbox.getValue().getStatus()).isEqualTo(EventInboxStatus.PROCESSED);
        assertThat(inbox.getValue().getTopic()).isEqualTo(TOPIC);
        assertThat(inbox.getValue().getOffset()).isEqualTo(42L);
        assertThat(inbox.getValue().getSource()).isEqualTo("grupo5");
        assertThat(inbox.getValue().getPayload()).isNull();
        assertThat(inbox.getValue().getProcessedAt()).isEqualTo(NOW);
    }

    @Test
    void descartaUnEventoYaProcesado() {
        when(inboxRepository.existsByEventIdAndStatus("e-1", EventInboxStatus.PROCESSED)).thenReturn(true);

        dispatcher.dispatch(consumerRecord("e-1"));

        verify(handler, never()).handle(any());
        verify(inboxRepository, never()).save(any());
    }

    @Test
    void siElHandlerFallaNoLoRegistraComoProcesado() {
        doThrow(new IllegalStateException("falló")).when(handler).handle(any());

        ConsumerRecord<String, Object> consumerRecord = consumerRecord("e-1");

        assertThatThrownBy(() -> dispatcher.dispatch(consumerRecord)).isInstanceOf(IllegalStateException.class);
        verify(inboxRepository, never()).save(any());
    }

    @Test
    void exponeLosTopicosDeLosHandlers() {
        assertThat(dispatcher.topics()).containsExactly(TOPIC);
    }

    @Test
    void dosHandlersParaElMismoTopicoEsUnErrorDeConfiguracion() {
        EventHandler otro = mock(EventHandler.class);
        when(otro.topic()).thenReturn(TOPIC);
        List<EventHandler> handlers = List.of(handler, otro);
        EventEnvelopeMapper envelopeMapper = new EventEnvelopeMapper(new ObjectMapper());
        Clock clock = Clock.systemUTC();

        assertThatThrownBy(() -> new EventDispatcher(handlers, envelopeMapper, inboxRepository, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TOPIC);
    }

    private static ConsumerRecord<String, Object> consumerRecord(String eventId) {
        return new ConsumerRecord<>(TOPIC, 0, 42L, "clave", AvroEvents.envelope(eventId, TOPIC, false));
    }
}
