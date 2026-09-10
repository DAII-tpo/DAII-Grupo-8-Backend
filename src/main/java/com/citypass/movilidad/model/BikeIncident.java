package com.citypass.movilidad.model;

import com.citypass.movilidad.model.enums.BikeIncidentStatus;
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

import java.time.Instant;

@Entity
@Table(name = "bike_incidents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BikeIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bike_id", nullable = false)
    private Bike bike;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reported_by_user_id", nullable = false)
    private User reportedByUser;

    // Nullable: null si el incidente no ocurrió durante un viaje (ver DER).
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "trip_id", nullable = true)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_type_id", nullable = false)
    private IncidentType incidentType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BikeIncidentStatus status = BikeIncidentStatus.OPEN;

    @CreationTimestamp
    @Column(name = "reported_at", nullable = false, updatable = false)
    private Instant reportedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    // Nullable: null hasta que el incidente se resuelve (ver DER).
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "resolved_by_user_id", nullable = true)
    private User resolvedByUser;

}
