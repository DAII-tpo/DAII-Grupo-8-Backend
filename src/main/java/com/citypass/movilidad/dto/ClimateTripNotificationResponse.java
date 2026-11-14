package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.ClimateTripNotification;

import java.time.Instant;

public record ClimateTripNotificationResponse(
        Long tripId, ClimateAlertResponse alerta,
        Long suggestedStationId, String suggestedStationName, Instant notifiedAt
) {
    public static ClimateTripNotificationResponse from(ClimateTripNotification notification) {
        var suggested = notification.getSuggestedStation();
        return new ClimateTripNotificationResponse(notification.getTrip().getId(),
                ClimateAlertResponse.from(notification.getAlert()),
                suggested == null ? null : suggested.getId(),
                suggested == null ? null : suggested.getName(),
                notification.getNotifiedAt());
    }
}
