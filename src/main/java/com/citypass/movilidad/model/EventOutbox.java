package com.citypass.movilidad.model;

import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.model.enums.EventOutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Evento a publicar en el bus (patrón transactional outbox): se guarda en la misma transacción que el
 * cambio de dominio y OutboxRelay lo publica después, con reintentos.
 */
@Entity
@Table(name = "event_outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventOutbox {

    @Id
    @UuidGenerator
    @Column(columnDefinition = "CHAR(36)")
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregate_type", nullable = false, length = 20)
    private AggregateType aggregateType;

    // Id de la entidad según aggregateType (sin FK, puede apuntar a distintas tablas).
    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    // Fqn del event type (com.citypass.movilidad.<Nombre>), que es también el tópico en Kafka.
    @Column(name = "event_type", nullable = false, length = 150)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private EventOutboxStatus status = EventOutboxStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "next_attempt_at", nullable = false)
    @Builder.Default
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error", length = 1000)
    private String lastError;

    // eventId que le asignó el gateway al publicarlo.
    @Column(name = "bus_event_id", columnDefinition = "CHAR(36)")
    private String busEventId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

}
