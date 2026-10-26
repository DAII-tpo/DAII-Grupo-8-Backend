package com.citypass.movilidad.model;

import com.citypass.movilidad.model.enums.EventInboxStatus;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Evento recibido del bus. La clave es el eventId del gateway, que permite descartar reentregas. */
@Entity
@Table(name = "event_inbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventInbox {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 150)
    private String eventType;

    // metadata.source: el sub del token de servicio de quien lo publicó.
    @Column(nullable = false, length = 100)
    private String source;

    @Column(nullable = false, length = 200)
    private String topic;

    @Column(name = "kafka_partition", nullable = false)
    private int partition;

    @Column(name = "kafka_offset", nullable = false)
    private long offset;

    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventInboxStatus status;

    // Solo si falló el procesamiento, para poder reprocesarlo.
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

}
