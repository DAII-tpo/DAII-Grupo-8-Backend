package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.enums.EmergencyPriority;
import com.citypass.movilidad.model.enums.EmergencyStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Pedido de ayuda (SOS) durante un viaje")
public record EmergencyResponse(
        @Schema(description = "ID de la emergencia", example = "91")
        Long id,
        @Schema(description = "Código para mostrar y para Emergencias", example = "EMG-20261008-0091")
        String code,
        @Schema(description = "PENDING: se puede cancelar o espera el cuestionario. REPORTED: se avisó a Emergencias")
        EmergencyStatus status,
        Long tripId,
        Long bikeId,
        String bikeCode,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal accuracyMeters,
        Instant reportedAt,
        @Schema(description = "Hasta cuándo se puede cancelar (reloj del servidor)")
        Instant cancelableUntil,
        @Schema(description = "Hasta cuándo se puede responder el cuestionario. Vencido, se avisa igual")
        Instant triageDeadline,
        @Schema(description = "Duración de la ventana de cancelación, para el contador del front", example = "10")
        long cancelWindowSeconds,
        @Schema(description = "Duración del plazo del cuestionario, para el contador del front", example = "60")
        long triageWindowSeconds,
        Boolean canMove,
        Boolean bleeding,
        Boolean vehicleInvolved,
        @Schema(description = "Prioridad sugerida, una vez enviado el cuestionario o vencido su plazo")
        EmergencyPriority priority,
        @Schema(description = "Cuándo se avisó a Emergencias")
        Instant sentAt
) {
}
