package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.ClimateTripNotification;
import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.model.enums.TripStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClimateTripNotificationRepository extends JpaRepository<ClimateTripNotification, Long> {
    @EntityGraph(attributePaths = {"alert", "trip", "suggestedStation"})
    List<ClimateTripNotification> findByTrip_User_IdAndTrip_StatusAndAlert_StatusOrderByNotifiedAtDesc(
            Long userId, TripStatus tripStatus, ClimateAlert.Status alertStatus);
}
