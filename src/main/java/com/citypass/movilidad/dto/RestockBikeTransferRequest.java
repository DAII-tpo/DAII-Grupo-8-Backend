package com.citypass.movilidad.dto;

import com.citypass.movilidad.validation.EntityId;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Set;

public record RestockBikeTransferRequest(
        @NotNull @EntityId Long sourceStationId,
        @NotNull @EntityId Long destinationStationId,
        @NotNull @EntityId Long restockTaskId,
        @NotEmpty Set<@NotNull @Positive Long> bikeIds
) {}
