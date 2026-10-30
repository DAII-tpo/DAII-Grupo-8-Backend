package com.citypass.movilidad.model;

import com.citypass.movilidad.model.enums.ObstructionImpactLevel;
import com.citypass.movilidad.model.enums.ObstructionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "ciclovia_obstructions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CicloviaObstruction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reclamo_id", nullable = false, unique = true, length = 64)
    private String reclamoId;

    @Column(name = "ciudadano_id", length = 64)
    private String ciudadanoId;

    @Column(length = 255)
    private String titulo;

    @Column(columnDefinition = "TEXT")
    private String descripcion;

    @Column(length = 255)
    private String direccion;

    @Column(length = 100)
    private String barrio;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Enumerated(EnumType.STRING)
    @Column(name = "impact_level", nullable = false, length = 20)
    @Builder.Default
    private ObstructionImpactLevel impactLevel = ObstructionImpactLevel.MEDIO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ObstructionStatus status = ObstructionStatus.ACTIVE;

    @Column(name = "affected_station_ids", length = 500)
    private String affectedStationIds;

    @Column(name = "detour_description", columnDefinition = "TEXT")
    private String detourDescription;

    @Column(name = "emergency_dispatched", nullable = false)
    @Builder.Default
    private boolean emergencyDispatched = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}

