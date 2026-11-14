package com.citypass.movilidad.dto;

import java.time.Instant;

public record EventArrivalIntentResponse(String eventId, Long stationId, Instant arrivalAt, String status) {
}
