package com.citypass.movilidad.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "climate_alerts")
@Getter
@Setter
public class ClimateAlert {
    public enum Severity { BAJA, MEDIA, ALTA, CRITICA }
    public enum Status { PROGRAMADA, ACTIVA, FINALIZADA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_alert_id", nullable = false, unique = true, length = 100)
    private String externalAlertId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Column(nullable = false, length = 50)
    private String phenomenon;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "actor_sub", length = 100)
    private String actorSub;

    @Column(name = "original_payload", columnDefinition = "json")
    private String originalPayload;

    @ManyToMany
    @JoinTable(name = "climate_alert_neighborhoods",
            joinColumns = @JoinColumn(name = "climate_alert_id"),
            inverseJoinColumns = @JoinColumn(name = "neighborhood_id"))
    private Set<Neighborhood> neighborhoods = new HashSet<>();
}
