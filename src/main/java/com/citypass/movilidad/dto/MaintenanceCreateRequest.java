package com.citypass.movilidad.dto;
import com.citypass.movilidad.validation.EntityId;
import jakarta.validation.constraints.*;
public record MaintenanceCreateRequest(@NotNull @EntityId Long bikeId, @EntityId Long incidentId,
                                       @NotBlank @Size(max=2000) String description) {}
