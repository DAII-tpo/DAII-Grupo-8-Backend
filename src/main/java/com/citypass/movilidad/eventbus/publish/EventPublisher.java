package com.citypass.movilidad.eventbus.publish;

import com.citypass.movilidad.eventbus.EventBusProperties;
import com.citypass.movilidad.model.EventOutbox;
import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.repository.EventOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Punto de entrada de los servicios de dominio para disparar un evento.
 * <p>
 * No llama al bus: guarda el evento en el outbox dentro de la transacción del cambio que lo origina, así
 * el evento existe si y solo si el cambio se confirmó. OutboxRelay lo publica después, con reintentos.
 * Por eso exige una transacción activa.
 */
@Component
public class EventPublisher {

    // Un evento nombra un hecho consumado en PascalCase (CONTRACTS.md del bus): ViajeFinalizado, no finalizarViaje.
    private static final Pattern EVENT_NAME = Pattern.compile("[A-Z][A-Za-z0-9]*");

    private final EventOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final EventBusProperties properties;

    public EventPublisher(EventOutboxRepository outboxRepository, ObjectMapper objectMapper,
                          EventBusProperties properties) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Encola un evento propio para publicarlo en el bus.
     *
     * @param eventName     nombre del event type sin namespace (ej. ViajeFinalizado); tiene que estar registrado
     * @param aggregateType entidad que originó el evento
     * @param aggregateId   id de esa entidad
     * @param data          campos de negocio; tienen que cumplir el schema del event type. Las fechas van
     *                      como epoch millis (long), que es lo que espera un campo timestamp-millis
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String eventName, AggregateType aggregateType, Object aggregateId, Object data) {
        if (eventName == null || !EVENT_NAME.matcher(eventName).matches()) {
            throw new IllegalArgumentException("Nombre de evento inválido (se espera PascalCase): " + eventName);
        }
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(data, "data");

        outboxRepository.save(EventOutbox.builder()
                .eventType(properties.fqn(eventName))
                .aggregateType(aggregateType)
                .aggregateId(aggregateId.toString())
                .payload(toJson(data))
                .build());
    }

    private String toJson(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Los datos del evento no se pueden serializar a JSON", ex);
        }
    }
}
