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

/**
 * Proyección de las alertas de Espacios; la idempotencia por eventId la aplica EventDispatcher.
 *
 * <p>Si la matriz indica bloqueo, al recibir la alerta (aunque quede PROGRAMADA) se abre una afectación
 * preventiva por estación para BI (EstacionUrbanaAfectada) y al finalizarla se restauran todas.
 *
 * <p>La alerta termina con la Finalizada de Espacios o, si no llega antes, al vencer su vigenciaHasta
 * ({@link #expireAlert}); los dos cierres tienen el mismo efecto.
 */
@Service
@Slf4j
public class ClimateAlertService {
    private final ClimateAlertRepository alerts;
    private final NeighborhoodRepository neighborhoods;
    private final ClimateAlertEffects effects;
    private final ClimateTripNotificationRepository notifications;
    private final AffectedStationsService affectedStations;
    private final ClimateAffectedStations stationsInArea;
    private final ClimatePolicyMatrix matrix;

    public ClimateAlertService(ClimateAlertRepository alerts, NeighborhoodRepository neighborhoods,
                               ClimateAlertEffects effects, ClimateTripNotificationRepository notifications,
                               AffectedStationsService affectedStations, ClimateAffectedStations stationsInArea,
                               ClimatePolicyMatrix matrix) {
        this.alerts = alerts;
        this.neighborhoods = neighborhoods;
        this.effects = effects;
        this.notifications = notifications;
        this.affectedStations = affectedStations;
        this.stationsInArea = stationsInArea;
        this.matrix = matrix;
    }

    @Transactional
    public void confirm(JsonNode data, Instant receivedAt) {
        String externalId = requiredText(data, "alertaId");
        if (alerts.findByExternalAlertId(externalId).isPresent()) {
            log.info("Alerta climática duplicada: {}", LogValues.safe(externalId));
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
                    () -> log.warn("Barrio de alerta climática sin correspondencia: {}", LogValues.safe(normalized)));
        }
        if (alert.hasExpired(now)) {
            // Llega ya vencida: se persiste (con sus barrios, para auditoría) para que un reenvío cuente como
            // duplicado, pero no bloquea ni publica nada.
            alert.setStatus(ClimateAlert.Status.FINALIZADA);
            alert.setFinalizedAt(until);
            alert.setFinalizationReason(ClimateAlert.FinalizationReason.VENCIMIENTO);
            alerts.saveAndFlush(alert);
            log.warn("Alerta climática {} recibida ya vencida (vigenciaHasta {}); no se aplica",
                    LogValues.safe(externalId), until);
            return;
        }
        alert.setStatus(from.isAfter(now) ? ClimateAlert.Status.PROGRAMADA : ClimateAlert.Status.ACTIVA);
        if (alert.getStatus() == ClimateAlert.Status.ACTIVA) {
            alert.setActivatedAt(now);
        }
        alerts.saveAndFlush(alert);
        blockStationsIfRequired(alert, now);
        if (alert.getStatus() == ClimateAlert.Status.ACTIVA) {
            effects.applied(alert, now);
        }
    }

    /**
     * Finalizada de Espacios. findByExternalAlertId toma el lock de fila, así que compite en orden con el
     * vencimiento: lo primero que llegue cierra la alerta y lo segundo se ignora.
     */
    @Transactional
    public void finish(JsonNode data) {
        String externalId = requiredText(data, "alertaId");
        alerts.findByExternalAlertId(externalId).ifPresentOrElse(alert -> {
            if (alert.getStatus() == ClimateAlert.Status.FINALIZADA) {
                log.info("Alerta climática {} ya finalizada ({}); se ignora la Finalizada de Espacios",
                        LogValues.safe(externalId), alert.getFinalizationReason());
                return;
            }
            close(alert, epochMillis(data, "finalizadaEn"), ClimateAlert.FinalizationReason.ESPACIOS);
        }, () -> log.warn("Finalización de alerta climática desconocida: {}", LogValues.safe(externalId)));
    }

    /** Vencimiento por vigenciaHasta. Lo llama el job, una alerta por transacción. */
    @Transactional
    public void expireAlert(Long id) {
        Instant now = Instant.now();
        ClimateAlert alert = alerts.findByIdForUpdate(id).orElse(null);
        if (alert == null || alert.getStatus() == ClimateAlert.Status.FINALIZADA || !alert.hasExpired(now)) {
            return; // Ya la cerró Espacios, o todavía no venció.
        }
        // Se cierra con la fecha real de fin, aunque el job corra tarde: así BI mide bien la duración.
        close(alert, alert.getValidUntil(), ClimateAlert.FinalizationReason.VENCIMIENTO);
    }

    /** Único camino de cierre, igual para la Finalizada de Espacios y para el vencimiento. */
    private void close(ClimateAlert alert, Instant finalizedAt, ClimateAlert.FinalizationReason reason) {
        if (alert.getStatus() == ClimateAlert.Status.FINALIZADA) {
            log.info("Alerta climática {} ya finalizada ({}); se ignora el cierre por {}",
                    LogValues.safe(alert.getExternalAlertId()), alert.getFinalizationReason(), reason);
            return;
        }
        boolean wasActive = alert.getStatus() == ClimateAlert.Status.ACTIVA;
        alert.setStatus(ClimateAlert.Status.FINALIZADA);
        alert.setFinalizedAt(finalizedAt);
        alert.setFinalizationReason(reason);
        alerts.save(alert);
        // Siempre: también una alerta PROGRAMADA pudo haber abierto afectaciones preventivas.
        affectedStations.restoreClimateAlert(alert.getExternalAlertId(), finalizedAt);
        if (wasActive) {
            effects.lifted(alert, finalizedAt);
        }
        log.info("Alerta climática {} finalizada por {}", LogValues.safe(alert.getExternalAlertId()), reason);
    }

    @Transactional
    public void activateAlert(Long id) {
        Instant now = Instant.now();
        ClimateAlert alert = alerts.findByIdForUpdate(id).orElse(null);
        if (alert == null || alert.getStatus() != ClimateAlert.Status.PROGRAMADA
                || alert.getValidFrom().isAfter(now)) {
            return;
        }
        if (alert.hasExpired(now)) {
            // Su vigencia ya pasó (p. ej. la app estuvo caída): no se activa, la cierra el job de vencimiento.
            // Así no se publica una Aplicada seguida de una Levantada en el mismo instante.
            return;
        }
        alert.setStatus(ClimateAlert.Status.ACTIVA);
        alert.setActivatedAt(now);
        alerts.save(alert);
        // Idempotente: solo agrega estaciones que pasaron a ACTIVE desde que se recibió la alerta.
        blockStationsIfRequired(alert, now);
        effects.applied(alert, now);
    }

    @Transactional(readOnly = true)
    public List<ClimateAlertResponse> visibleAlerts() {
        Instant now = Instant.now();
        return alerts.findByStatusIn(Arrays.asList(ClimateAlert.Status.PROGRAMADA, ClimateAlert.Status.ACTIVA))
                .stream()
                // Desde vigenciaHasta deja de mostrarse, aunque el job todavía no la haya finalizado.
                .filter(alert -> !alert.hasExpired(now))
                // Las ACTIVA se muestran todas; las PROGRAMADA, solo si su respuesta amerita aviso anticipado.
                .filter(alert -> alert.getStatus() == ClimateAlert.Status.ACTIVA
                        || matrix.responseFor(alert).showsEarlyWarning())
                .map(alert -> ClimateAlertResponse.from(alert, matrix.responseFor(alert)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClimateTripNotificationResponse> tripNotifications(Long userId) {
        Instant now = Instant.now();
        return notifications.findByTrip_User_IdAndTrip_StatusAndAlert_StatusOrderByNotifiedAtDesc(
                        userId, TripStatus.ACTIVE, ClimateAlert.Status.ACTIVA)
                .stream()
                .filter(notice -> notice.getAlert().isInForce(now))
                .map(notice -> ClimateTripNotificationResponse.from(notice, matrix.responseFor(notice.getAlert())))
                .toList();
    }

    private void blockStationsIfRequired(ClimateAlert alert, Instant now) {
        if (matrix.responseFor(alert).blocksRental()) {
            affectedStations.blockForClimateAlert(alert, stationsInArea.of(alert), now);
        }
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
