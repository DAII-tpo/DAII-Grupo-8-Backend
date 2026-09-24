package com.citypass.movilidad.dto;
import com.citypass.movilidad.validation.EntityId;
import jakarta.validation.constraints.*;

/** Cierre de mantenimiento. stationId es opcional: si falta, la bici vuelve a su estación de origen. */
public record MaintenanceCompleteRequest(@NotBlank @Size(max=2000) String resolution, @EntityId Long stationId) {
    public MaintenanceCompleteRequest(String resolution) {
        this(resolution, null);
    }
}
