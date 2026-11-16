package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.ClimateAlertResponse;
import com.citypass.movilidad.dto.ClimateTripNotificationResponse;
import com.citypass.movilidad.dto.StationClimateStatusResponse;
import com.citypass.movilidad.config.CurrentUserId;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.service.ClimateAlertService;
import com.citypass.movilidad.service.ClimatePolicyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ClimateAlertController {
    private final ClimateAlertService alerts;
    private final ClimatePolicyService policy;
    private final StationRepository stations;

    public ClimateAlertController(ClimateAlertService alerts, ClimatePolicyService policy,
                                  StationRepository stations) {
        this.alerts = alerts;
        this.policy = policy;
        this.stations = stations;
    }

    @GetMapping("/climate-alerts/current")
    public List<ClimateAlertResponse> current() {
        return alerts.visibleAlerts();
    }

    @GetMapping("/stations/{id}/climate-status")
    public StationClimateStatusResponse stationStatus(@PathVariable Long id) {
        var station = stations.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Estación no encontrada: " + id));
        return policy.stationStatus(station);
    }

    @GetMapping("/stations/climate-status")
    public List<StationClimateStatusResponse> affectedStationStatuses() {
        return policy.affectedStationStatuses();
    }

    @GetMapping("/climate-alerts/trip-notifications")
    public List<ClimateTripNotificationResponse> tripNotifications(@CurrentUserId Long userId) {
        return alerts.tripNotifications(userId);
    }
}
