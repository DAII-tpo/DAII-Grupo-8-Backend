package com.citypass.movilidad.eventbus.publish;

import com.citypass.movilidad.eventbus.EventBusTestProperties;
import com.citypass.movilidad.model.EventOutbox;
import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.model.enums.EventOutboxStatus;
import com.citypass.movilidad.repository.EventOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class EventPublisherTest {

    private EventOutboxRepository repository;
    private EventPublisher publisher;

    @BeforeEach
    void setUp() {
        repository = mock(EventOutboxRepository.class);
        publisher = new EventPublisher(repository, new ObjectMapper(), EventBusTestProperties.properties());
    }

    @Test
    void guardaElEventoEnElOutboxConElFqnYLosDatosEnJson() {
        publisher.publish("ViajeFinalizado", AggregateType.TRIP, 7L, new ViajeFinalizado(7L, 35));

        ArgumentCaptor<EventOutbox> saved = ArgumentCaptor.forClass(EventOutbox.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEventType()).isEqualTo("com.citypass.movilidad.ViajeFinalizado");
        assertThat(saved.getValue().getAggregateType()).isEqualTo(AggregateType.TRIP);
        assertThat(saved.getValue().getAggregateId()).isEqualTo("7");
        assertThat(saved.getValue().getPayload()).isEqualTo("{\"tripId\":7,\"duracionMin\":35}");
        assertThat(saved.getValue().getStatus()).isEqualTo(EventOutboxStatus.PENDING);
        assertThat(saved.getValue().getAttempts()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"viajeFinalizado", "Viaje_Finalizado", "com.citypass.movilidad.ViajeFinalizado", ""})
    void rechazaNombresQueNoSonPascalCase(String eventName) {
        assertThatThrownBy(() -> publisher.publish(eventName, AggregateType.TRIP, 7L, new ViajeFinalizado(7L, 35)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void exigeLosDatosDelEvento() {
        assertThatThrownBy(() -> publisher.publish("ViajeFinalizado", AggregateType.TRIP, 7L, null))
                .isInstanceOf(NullPointerException.class);
    }

    record ViajeFinalizado(long tripId, int duracionMin) {
    }
}
