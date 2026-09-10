package com.citypass.movilidad.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationDTO {

    @Schema(description = "ID de la estación", example = "1")
    private Long id;
    @Schema(description = "Nombre de la estación", example = "Estación 9 de Julio")
    private String name;
    @Schema(description = "Dirección de la estación", example = "Av. 9 de Julio 1030")
    private String address;
    @Schema(description = "Latitud de la estación", example = "40.7128")
    private BigDecimal latitude;
    @Schema(description = "Longitud de la estación", example = "-74.0060")
    private BigDecimal longitude;
    @Schema(description = "Capacidad de la estación", example = "10")
    private Integer capacity;
    @Schema(description = "Estado de la estación", example = "ACTIVE")
    private String status;
    @Schema(description = "Fecha de creación de la estación", example = "2026-09-04T00:07:02Z")
    private String createdAt;
    @Schema(description = "Fecha de actualización de la estación", example = "2026-09-04T00:07:02Z")
    private String updatedAt;
    @Schema(description = "Fecha de eliminación de la estación", example = "2026-09-04T00:07:02Z")
    private String deletedAt;

}
