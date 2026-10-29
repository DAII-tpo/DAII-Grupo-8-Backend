package com.citypass.movilidad.dto;

import com.citypass.movilidad.validation.EntityId;
import com.citypass.movilidad.validation.Latitude;
import com.citypass.movilidad.validation.Longitude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
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
        @NotBlank @Size(max = 2000) String description,
        @Schema(description = "Latitud del usuario al reportar (opcional, del navegador)", example = "-34.6037")
        @Latitude Double latitude,
        @Schema(description = "Longitud del usuario al reportar (opcional, del navegador)", example = "-58.3816")
        @Longitude Double longitude,
        @Schema(description = "Precisión de la ubicación en metros (coords.accuracy del navegador)", example = "15")
        @DecimalMin("0.0") Double accuracyMeters
) {

    /** Reporte sin ubicación. */
    public IncidentCreateRequest(Long bikeId, Long incidentTypeId, String description) {
        this(bikeId, incidentTypeId, description, null, null, null);
    }

    /** La ubicación va completa (latitud y longitud) o no va. */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "latitude y longitude van juntas")
    public boolean isLocationComplete() {
        return (latitude == null) == (longitude == null);
    }

    @JsonIgnore
    public boolean hasLocation() {
        return latitude != null && longitude != null;
    }
}
