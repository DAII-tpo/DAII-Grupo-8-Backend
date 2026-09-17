package com.citypass.movilidad.dto;
import jakarta.validation.constraints.*;
public record MaintenanceCompleteRequest(@NotBlank @Size(max=2000) String resolution) {}
