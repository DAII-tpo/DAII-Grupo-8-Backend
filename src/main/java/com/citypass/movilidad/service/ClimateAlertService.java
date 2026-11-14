package com.citypass.movilidad.service;

import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.dto.ClimateAlertResponse;
import com.citypass.movilidad.dto.ClimateTripNotificationResponse;
import com.citypass.movilidad.model.enums.TripStatus;
import com.citypass.movilidad.repository.ClimateTripNotificationRepository;
import com.citypass.movilidad.repository.ClimateAlertRepository;
import com.citypass.movilidad.repository.NeighborhoodRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Proyección de las alertas de Espacios; la idempotencia por eventId la aplica EventDispatcher. */
@Service
@Slf4j
public class ClimateAlertService {
    private final ClimateAlertRepository alerts;
    private final NeighborhoodRepository neighborhoods;
    private final ClimateAlertEffects effects;
    private final ClimateTripNotificationRepository notifications;

    public ClimateAlertService(ClimateAlertRepository alerts, NeighborhoodRepository neighborhoods,
                               ClimateAlertEffects effects, ClimateTripNotificationRepository notifications) {
        this.alerts = alerts;
        this.neighborhoods = neighborhoods;
        this.effects = effects;
        this.notifications = notifications;
    }

    @Transactional
    public void confirm(JsonNode data, Instant receivedAt) {
        String externalId = requiredText(data, "alertaId");
        if (alerts.findByExternalAlertId(externalId).isPresent()) {
            log.info("Alerta climática duplicada: {}", externalId);
            return;
        }
        Instant from = epochMillis(data, "vigenciaDesde");
        Instant until = epochMillis(data, "vigenciaHasta");
        if (!until.isAfter(from)) {
            throw new IllegalArgumentException("vigenciaHasta debe ser posterior a vigenciaDesde");
        }
        ClimateAlert alert = new ClimateAlert();
        alert.setExternalAlertId(externalId);
        alert.setSeverity(parseSeverity(requiredText(data, "nivelCriticidad")));
        alert.setPhenomenon(requiredText(data, "fenomeno"));
        alert.setValidFrom(from);
        alert.setValidUntil(until);
        alert.setReceivedAt(receivedAt);
        alert.setActorSub(optionalText(data, "actorSub"));
        alert.setOriginalPayload(data.toString());
        Instant now = Instant.now();
        alert.setStatus(from.isAfter(now) ? ClimateAlert.Status.PROGRAMADA : ClimateAlert.Status.ACTIVA);
        if (alert.getStatus() == ClimateAlert.Status.ACTIVA) {
            alert.setActivatedAt(now);
        }
        JsonNode affected = data.path("barrios");
        if (!affected.isArray()) {
            throw new IllegalArgumentException("barrios debe ser una lista");
        }
        for (JsonNode name : affected) {
            if (!name.isTextual()) {
                throw new IllegalArgumentException("Cada barrio debe ser un nombre");
            }
            String normalized = NeighborhoodNames.normalize(name.asText());
            neighborhoods.findByNormalizedName(normalized).ifPresentOrElse(
                    alert.getNeighborhoods()::add,
                    () -> log.warn("Barrio de alerta climática sin correspondencia: {}", normalized));
        }
        alerts.saveAndFlush(alert);
        if (alert.getStatus() == ClimateAlert.Status.ACTIVA) {
            effects.applied(alert, now);
        }
    }

    @Transactional
    public void finish(JsonNode data) {
        String externalId = requiredText(data, "alertaId");
        alerts.findByExternalAlertId(externalId).ifPresentOrElse(alert -> {
            if (alert.getStatus() == ClimateAlert.Status.FINALIZADA) {
                return;
            }
            boolean wasActive = alert.getStatus() == ClimateAlert.Status.ACTIVA;
            alert.setStatus(ClimateAlert.Status.FINALIZADA);
            alert.setFinalizedAt(epochMillis(data, "finalizadaEn"));
            alerts.save(alert);
            if (wasActive) {
                effects.lifted(alert, alert.getFinalizedAt());
            }
        }, () -> log.warn("Finalización de alerta climática desconocida: {}", externalId));
    }

    @Transactional
    public void activateAlert(Long id) {
        Instant now = Instant.now();
        ClimateAlert alert = alerts.findByIdForUpdate(id).orElse(null);
        if (alert == null || alert.getStatus() != ClimateAlert.Status.PROGRAMADA
                || alert.getValidFrom().isAfter(now)) {
            return;
        }
        alert.setStatus(ClimateAlert.Status.ACTIVA);
        alert.setActivatedAt(now);
        alerts.save(alert);
        effects.applied(alert, now);
    }

    @Transactional(readOnly = true)
    public List<ClimateAlertResponse> visibleAlerts() {
        return alerts.findByStatusIn(Arrays.asList(ClimateAlert.Status.PROGRAMADA, ClimateAlert.Status.ACTIVA))
                .stream()
                .filter(alert -> alert.getStatus() == ClimateAlert.Status.ACTIVA
                        || alert.getSeverity() != ClimateAlert.Severity.BAJA)
                .map(ClimateAlertResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClimateTripNotificationResponse> tripNotifications(Long userId) {
        return notifications.findByTrip_User_IdAndTrip_StatusAndAlert_StatusOrderByNotifiedAtDesc(
                        userId, TripStatus.ACTIVE, ClimateAlert.Status.ACTIVA)
                .stream().map(ClimateTripNotificationResponse::from).toList();
    }

    private static ClimateAlert.Severity parseSeverity(String severity) {
        try {
            return ClimateAlert.Severity.valueOf(severity);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("nivelCriticidad desconocido: " + severity, ex);
        }
    }

    private static String requiredText(JsonNode data, String key) {
        JsonNode value = data.path(key);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(key + " es obligatorio");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode data, String key) {
        JsonNode value = data.path(key);
        return value.isTextual() ? value.asText() : null;
    }

    private static Instant epochMillis(JsonNode data, String key) {
        JsonNode value = data.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(key + " debe ser epoch millis");
        }
        return Instant.ofEpochMilli(value.longValue());
    }
}
