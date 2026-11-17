package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.ClimateTripNotification;
import com.citypass.movilidad.service.ClimateResponse;

import java.time.Instant;

public record ClimateTripNotificationResponse(
        Long tripId, ClimateAlertResponse alerta,
        Long suggestedStationId, String suggestedStationName, Instant notifiedAt
) {
    public static ClimateTripNotificationResponse from(ClimateTripNotification notification, ClimateResponse response) {
        var suggested = notification.getSuggestedStation();
        return new ClimateTripNotificationResponse(notification.getTrip().getId(),
                ClimateAlertResponse.from(notification.getAlert(), response),
                suggested == null ? null : suggested.getId(),
                suggested == null ? null : suggested.getName(),
                notification.getNotifiedAt());
    }
}
