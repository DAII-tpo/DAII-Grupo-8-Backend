package com.citypass.movilidad.dto.request;

import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.validation.Latitude;
import com.citypass.movilidad.validation.Longitude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Datos para crear o actualizar una estación. Los límites coinciden con las columnas de la tabla. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Datos para crear o actualizar una estación")
public class StationRequestDTO {

    @Schema(description = "Nombre de la estación", example = "Estación 9 de Julio",
            requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 150)
    @NotBlank
    @Size(max = 150)
    private String name;

    @Schema(description = "Dirección de la estación", example = "Av. 9 de Julio 1030", maxLength = 255)
    @Size(max = 255)
    private String address;

    @Schema(description = "Latitud de la estación", example = "-34.6037",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Latitude
    private BigDecimal latitude;

    @Schema(description = "Longitud de la estación", example = "-58.3816",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Longitude
    private BigDecimal longitude;

    @Schema(description = "Cantidad de anclajes de la estación", example = "10",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    @Max(1000)
    private Integer capacity;

    @Schema(description = "Estado de la estación. Si se omite se utiliza ACTIVE", example = "ACTIVE")
    private StationStatus status;

    @Schema(description = "Origen de la estación. Si se omite se utiliza MANUAL", example = "MANUAL")
    private StationSource source;

}
