package com.citypass.movilidad.eventbus.consume;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventBusListenerTest {

    private static final String TOPIC = "com.citypass.emergencias.EmergenciaReportada";

    private ConcurrentKafkaListenerContainerFactory<Object, Object> factory;
    private ConcurrentMessageListenerContainer<Object, Object> container;
    private EventDispatcher dispatcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        factory = mock(ConcurrentKafkaListenerContainerFactory.class);
        container = mock(ConcurrentMessageListenerContainer.class);
        dispatcher = mock(EventDispatcher.class);
        when(factory.createContainer(any(String[].class))).thenReturn(container);
    }

    @Test
    void seSuscribeALosTopicosDeLosHandlersYArrancaElContenedor() {
        when(dispatcher.topics()).thenReturn(List.of(TOPIC));
        EventBusListener listener = new EventBusListener(factory, dispatcher);

        listener.start();
        listener.start();

        verify(factory, times(1)).createContainer(TOPIC);
        verify(container).setupMessageListener(any());
        verify(container).start();
        assertThat(listener.isRunning()).isTrue();

        listener.stop();

        verify(container).stop();
        assertThat(listener.isRunning()).isFalse();
    }

    @Test
    void sinHandlersNoSeConectaAlBroker() {
        when(dispatcher.topics()).thenReturn(List.of());
        EventBusListener listener = new EventBusListener(factory, dispatcher);

        listener.start();
        listener.stop();

        verify(factory, never()).createContainer(any(String[].class));
        assertThat(listener.isRunning()).isFalse();
    }
}
