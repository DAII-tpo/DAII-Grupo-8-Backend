package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.CulturalDestination;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Evento cultural de Espacios Públicos que sirve como destino de la ruta")
public record CulturalDestinationResponse(
        @Schema(description = "Id del evento en Espacios Públicos") String eventId,
        String title,
        String zone,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant startsAt,
        Instant endsAt,
        @Schema(description = "Cupo del evento") Integer maxCapacity,
        @Schema(description = "Inscriptos conocidos: demanda esperada hacia el destino") Integer registrations,
        @Schema(description = "Demanda de referencia: inscriptos o cupo si ACTIVE_FULL") int expectedDemand) {

    public static CulturalDestinationResponse from(CulturalDestination destination) {
        return new CulturalDestinationResponse(destination.getEventId(), destination.getTitle(),
                destination.getZone(), destination.getAddress(), destination.getLatitude(),
                destination.getLongitude(), destination.getStartsAt(), destination.getEndsAt(),
                destination.getMaxCapacity(), destination.getRegistrations(), destination.expectedDemand());
    }
}
