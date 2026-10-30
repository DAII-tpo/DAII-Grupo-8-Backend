package com.citypass.movilidad.model;

import com.citypass.movilidad.model.enums.EmergencyPriority;
import com.citypass.movilidad.model.enums.EmergencyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/** Pedido de ayuda (SOS) durante un viaje. */
@Entity
@Table(name = "bike_emergencies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BikeEmergency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bike_id", nullable = false)
    private Bike bike;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmergencyStatus status;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "accuracy_meters", precision = 9, scale = 2)
    private BigDecimal accuracyMeters;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    @Column(name = "cancelable_until", nullable = false)
    private Instant cancelableUntil;

    @Column(name = "triage_deadline", nullable = false)
    private Instant triageDeadline;

    // Respuestas del cuestionario; null = sin responder.
    @Column(name = "can_move")
    private Boolean canMove;

    @Column
    private Boolean bleeding;

    @Column(name = "vehicle_involved")
    private Boolean vehicleInvolved;

    @Enumerated(EnumType.STRING)
    @Column(length = 2)
    private EmergencyPriority priority;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    // Cuándo se publicó el evento EmergenciaBicicleta.
    @Column(name = "sent_at")
    private Instant sentAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

}
