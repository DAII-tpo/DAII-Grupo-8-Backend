package com.citypass.movilidad.dto;
import com.citypass.movilidad.validation.EntityId;
import jakarta.validation.constraints.*;

/**
 * @param stationId opcional: estación donde se devuelve la bicicleta. Si se omite, vuelve a la estación de
 *                  la que se retiró al abrir el mantenimiento.
 */
public record MaintenanceCompleteRequest(@NotBlank @Size(max=2000) String resolution, @EntityId Long stationId) {
    public MaintenanceCompleteRequest(String resolution) {
        this(resolution, null);
    }
}
