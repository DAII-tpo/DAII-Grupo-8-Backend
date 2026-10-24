package com.citypass.movilidad.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Evento cultural publicado por Espacios Públicos: un destino de la ruta urbana
 * (CU-INT-01, paso 3). Se arma solo con los eventos del bus; no se edita a mano.
 */
@Entity
@Table(name = "cultural_destinations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CulturalDestination {

    /** Id del evento en el módulo de Espacios Públicos. */
    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "space_id", nullable = false, length = 64)
    private String spaceId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 120)
    private String zone;

    @Column(length = 255)
    private String address;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "max_capacity", nullable = false)
    private Integer maxCapacity;

    /** Inscriptos conocidos: es la demanda esperada de bicis hacia el destino. */
    @Column(nullable = false)
    @Builder.Default
    private Integer registrations = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean cancelled = false;

    @Column(name = "space_enabled", nullable = false)
    @Builder.Default
    private boolean spaceEnabled = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Sirve como destino de la ruta: no cancelado, espacio habilitado y con ubicación. */
    public boolean isRoutable() {
        return !cancelled && spaceEnabled && latitude != null && longitude != null;
    }
}
