package com.citypass.movilidad.eventbus.publish;

import com.citypass.movilidad.eventbus.EventBusTestProperties;
import com.citypass.movilidad.model.EventOutbox;
import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.model.enums.EventOutboxStatus;
import com.citypass.movilidad.repository.EventOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final String FQN = "com.citypass.movilidad.ViajeFinalizado";

    private EventOutboxRepository repository;
    private EventGatewayClient gatewayClient;
    private OutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(EventOutboxRepository.class);
        gatewayClient = mock(EventGatewayClient.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
        relay = new OutboxRelay(repository, gatewayClient, transactionTemplate,
                EventBusTestProperties.properties(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void publicaElEventoYLoMarcaComoPublicado() {
        EventOutbox event = pending(0);
        when(repository.findNextPendingForUpdate(NOW)).thenReturn(Optional.of(event));
        when(gatewayClient.publish(FQN, "{\"tripId\":7}")).thenReturn("e-1");

        assertThat(relay.publishNext()).isTrue();

        assertThat(event.getStatus()).isEqualTo(EventOutboxStatus.PUBLISHED);
        assertThat(event.getBusEventId()).isEqualTo("e-1");
        assertThat(event.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void sinPendientesNoHaceNada() {
        when(repository.findNextPendingForUpdate(NOW)).thenReturn(Optional.empty());

        assertThat(relay.publishNext()).isFalse();
    }

    @Test
    void unErrorReintentableLoReprogramaConBackoff() {
        EventOutbox event = pending(1);
        when(repository.findNextPendingForUpdate(NOW)).thenReturn(Optional.of(event));
        when(gatewayClient.publish(any(), any())).thenThrow(new EventGatewayException("HTTP 503", 503, true, null));

        relay.publishNext();

        assertThat(event.getStatus()).isEqualTo(EventOutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(2);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(event.getLastError()).isEqualTo("HTTP 503");
    }

    @Test
    void unErrorNoReintentableLoMarcaComoFallidoEnElPrimerIntento() {
        EventOutbox event = pending(0);
        when(repository.findNextPendingForUpdate(NOW)).thenReturn(Optional.of(event));
        when(gatewayClient.publish(any(), any())).thenThrow(new EventGatewayException("HTTP 400", 400, false, null));

        relay.publishNext();

        assertThat(event.getStatus()).isEqualTo(EventOutboxStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(1);
    }

    @Test
    void alAgotarLosIntentosLoMarcaComoFallido() {
        EventOutbox event = pending(2);
        when(repository.findNextPendingForUpdate(NOW)).thenReturn(Optional.of(event));
        when(gatewayClient.publish(any(), any())).thenThrow(new IllegalStateException("inesperado"));

        relay.publishNext();

        assertThat(event.getStatus()).isEqualTo(EventOutboxStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(event.getLastError()).contains("inesperado");
    }

    @Test
    void elBackoffSeDuplicaHastaElTope() {
        assertThat(relay.backoff(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(relay.backoff(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(relay.backoff(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(relay.backoff(4)).isEqualTo(Duration.ofSeconds(30));
        assertThat(relay.backoff(50)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void publicaHastaVaciarLosPendientesSinPasarseDelLote() {
        when(repository.findNextPendingForUpdate(NOW))
                .thenReturn(Optional.of(pending(0)), Optional.of(pending(0)), Optional.empty());
        when(gatewayClient.publish(any(), any())).thenReturn("e-1");

        relay.publishPending();

        verify(gatewayClient, times(2)).publish(any(), any());
        verify(repository, times(3)).findNextPendingForUpdate(NOW);
    }

    private static EventOutbox pending(int attempts) {
        return EventOutbox.builder()
                .id("outbox-1")
                .aggregateType(AggregateType.TRIP)
                .aggregateId("7")
                .eventType(FQN)
                .payload("{\"tripId\":7}")
                .attempts(attempts)
                .build();
    }
}
