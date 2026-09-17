package com.citypass.movilidad.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Tipo de incidencia disponible para reportar")
public record IncidentTypeResponse(Long id, String code, String name, String description) {
}
