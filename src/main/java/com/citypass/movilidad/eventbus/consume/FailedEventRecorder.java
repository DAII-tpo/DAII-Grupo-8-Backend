package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.model.EventInbox;
import com.citypass.movilidad.model.enums.EventInboxStatus;
import com.citypass.movilidad.repository.EventInboxRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Destino de los eventos que no se pudieron procesar después de los reintentos. Quedan en el inbox como
 * FAILED, con su payload y el error, para poder revisarlos y reprocesarlos; el consumo sigue con el
 * siguiente evento en lugar de trabar la partición.
 */
public class FailedEventRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(FailedEventRecorder.class);

    private static final int MAX_ERROR_LENGTH = 1000;

    private final EventEnvelopeMapper envelopeMapper;
    private final EventInboxRepository inboxRepository;
    private final Clock clock;

    public FailedEventRecorder(EventEnvelopeMapper envelopeMapper, EventInboxRepository inboxRepository,
                               Clock clock) {
        this.envelopeMapper = envelopeMapper;
        this.inboxRepository = inboxRepository;
        this.clock = clock;
    }

    @Transactional
    public void record(ConsumerRecord<?, ?> consumerRecord, Exception failure) {
        Throwable cause = rootCause(failure);
        IncomingEvent event;
        try {
            event = envelopeMapper.map(consumerRecord);
        } catch (InvalidEventException ex) {
            // Sin envelope no hay eventId ni datos que guardar (por ejemplo, un mensaje que no es Avro).
            LOG.error("Mensaje inválido descartado: topic={}, partition={}, offset={}, error={}",
                    consumerRecord.topic(), consumerRecord.partition(), consumerRecord.offset(), cause.toString());
            return;
        }

        inboxRepository.save(EventInbox.builder()
                .eventId(event.metadata().eventId())
                .eventType(event.metadata().eventType())
                .source(event.metadata().source())
                .topic(consumerRecord.topic())
                .partition(consumerRecord.partition())
                .offset(consumerRecord.offset())
                .payloadHash(event.metadata().payloadHash())
                .status(EventInboxStatus.FAILED)
                .payload(event.data().toString())
                .lastError(truncate(cause.toString()))
                .receivedAt(event.metadata().receivedAt())
                .processedAt(clock.instant())
                .build());
        LOG.error("Evento registrado como FAILED tras agotar los reintentos: eventId={}, topic={}",
                event.metadata().eventId(), consumerRecord.topic(), cause);
    }

    // El error handler de Spring envuelve la excepción del handler en una ListenerExecutionFailedException.
    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
