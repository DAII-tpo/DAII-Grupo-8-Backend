package com.citypass.movilidad.dto;

import com.citypass.movilidad.validation.EntityId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Reporte de una incidencia sobre una bicicleta")
public record IncidentCreateRequest(
        @Schema(description = "ID de la bicicleta", example = "12")
        @NotNull @EntityId Long bikeId,
        @Schema(description = "ID del tipo de incidencia", example = "1")
        @NotNull @EntityId Long incidentTypeId,
        @Schema(description = "Detalle del problema", example = "La rueda trasera está desinflada")
        @NotBlank @Size(max = 2000) String description
) {
}
