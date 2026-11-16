package com.citypass.movilidad.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record EventArrivalIntentRequest(@NotNull Long stationId, @NotNull Instant arrivalAt) {
}
