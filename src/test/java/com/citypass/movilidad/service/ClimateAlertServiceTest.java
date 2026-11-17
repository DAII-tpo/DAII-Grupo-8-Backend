package com.citypass.movilidad.service;

import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.model.Neighborhood;
import com.citypass.movilidad.repository.ClimateAlertRepository;
import com.citypass.movilidad.repository.ClimateTripNotificationRepository;
import com.citypass.movilidad.repository.NeighborhoodRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class ClimateAlertServiceTest {
    private final ClimateAlertRepository repository = mock(ClimateAlertRepository.class);
    private final NeighborhoodRepository neighborhoods = mock(NeighborhoodRepository.class);
    private final ClimateAlertEffects effects = mock(ClimateAlertEffects.class);
    private final ClimateTripNotificationRepository notifications = mock(ClimateTripNotificationRepository.class);
    private final AffectedStationsService affectedStations = mock(AffectedStationsService.class);
    private final ClimateAffectedStations stationsInArea = mock(ClimateAffectedStations.class);
    private final ClimateAlertService service = new ClimateAlertService(repository, neighborhoods, effects,
            notifications, affectedStations, stationsInArea, new ClimatePolicyMatrix());
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource(value = {
            "alertaId|null|alertaId es obligatorio",
            "alertaId|\" \"|alertaId es obligatorio",
            "fenomeno|42|fenomeno es obligatorio",
            "nivelCriticidad|\"EXTREMA\"|nivelCriticidad desconocido",
            "vigenciaDesde|\"1794657600000\"|vigenciaDesde debe ser epoch millis",
            "vigenciaHasta|9223372036854775808|vigenciaHasta debe ser epoch millis",
            "vigenciaHasta|0|vigenciaHasta debe ser posterior",
            "barrios|null|barrios debe ser una lista",
            "barrios|[42]|Cada barrio debe ser un nombre"
    }, delimiter = '|')
    void invalidEventsAreRejectedBeforePersistingOrApplyingEffects(String field, String json, String message)
            throws Exception {
        var data = validEvent();
        data.set(field, mapper.readTree(json));

        assertThatThrownBy(() -> service.confirm(data, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);

        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(effects, affectedStations);
    }

    @Test
    void unknownNeighborhoodIsNotInventedAndActorIsPreserved() throws Exception {
        var data = validEvent();
        data.put("actorSub", "operador-espacios");
        data.putArray("barrios").add("Barrio desconocido");

        service.confirm(data, Instant.now());

        ArgumentCaptor<ClimateAlert> saved = ArgumentCaptor.forClass(ClimateAlert.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getNeighborhoods()).isEmpty();
        assertThat(saved.getValue().getActorSub()).isEqualTo("operador-espacios");
        verify(effects).applied(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = ClimateAlert.Status.class, names = {"ACTIVA", "FINALIZADA"})
    void scheduledActivationDoesNotReapplyAnAlreadyProcessedAlert(ClimateAlert.Status status) {
        ClimateAlert alert = new ClimateAlert();
        alert.setStatus(status);
        when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(alert));

        service.activateAlert(7L);

        verify(repository, never()).save(any());
        verifyNoInteractions(effects, affectedStations);
    }

    @Test
    void missingOrFutureAlertDoesNotActivateEarly() {
        service.activateAlert(7L);
        ClimateAlert future = new ClimateAlert();
        future.setStatus(ClimateAlert.Status.PROGRAMADA);
        future.setValidFrom(Instant.now().plusSeconds(3600));
        when(repository.findByIdForUpdate(8L)).thenReturn(Optional.of(future));

        service.activateAlert(8L);

        verify(repository, never()).save(any());
        verifyNoInteractions(effects);
    }

    @Test
    void finalizedAndUnknownAlertsDoNotEmitAnotherLiftedEvent() throws Exception {
        ClimateAlert finished = new ClimateAlert();
        finished.setStatus(ClimateAlert.Status.FINALIZADA);
        when(repository.findByExternalAlertId("weather-1")).thenReturn(Optional.of(finished));

        service.finish(mapper.readTree("{\"alertaId\":\"weather-1\"}"));
        service.finish(mapper.readTree("{\"alertaId\":\"unknown\"}"));

        verify(repository, never()).save(any());
        verifyNoInteractions(effects, affectedStations);
    }

    @Test
    void scheduledAlertCanFinishWithoutClaimingStationsWereRestored() throws Exception {
        ClimateAlert scheduled = new ClimateAlert();
        scheduled.setExternalAlertId("weather-1");
        scheduled.setStatus(ClimateAlert.Status.PROGRAMADA);
        when(repository.findByExternalAlertId("weather-1")).thenReturn(Optional.of(scheduled));
        Instant finishedAt = Instant.parse("2026-11-14T12:00:00Z");

        service.finish(mapper.readTree("{\"alertaId\":\"weather-1\",\"finalizadaEn\":"
                + finishedAt.toEpochMilli() + "}"));

        assertThat(scheduled.getStatus()).isEqualTo(ClimateAlert.Status.FINALIZADA);
        assertThat(scheduled.getFinalizedAt()).isEqualTo(finishedAt);
        assertThat(scheduled.getFinalizationReason()).isEqualTo(ClimateAlert.FinalizationReason.ESPACIOS);
        verify(repository).save(scheduled);
        // No se publica AlertaClimaticaLevantada, pero sí se cierran las afectaciones preventivas para BI.
        verifyNoInteractions(effects);
        verify(affectedStations).restoreClimateAlert("weather-1", finishedAt);
    }

    @Test
    void visibleAlertsHideOnlyScheduledAlertsWhoseResponseIsAPlainWarning() {
        ClimateAlert activeLow = visibleAlert("activa-baja", ClimateAlert.Status.ACTIVA, ClimateAlert.Severity.BAJA);
        ClimateAlert scheduledHigh = visibleAlert("programada-alta", ClimateAlert.Status.PROGRAMADA, ClimateAlert.Severity.ALTA);
        ClimateAlert scheduledLow = visibleAlert("programada-baja", ClimateAlert.Status.PROGRAMADA, ClimateAlert.Severity.BAJA);
        // Lluvia MEDIA es solo AVISO; huracán BAJA ya pide confirmación y se anticipa.
        ClimateAlert scheduledRain = visibleAlert("programada-lluvia", ClimateAlert.Status.PROGRAMADA,
                ClimateAlert.Severity.MEDIA);
        scheduledRain.setPhenomenon("LLUVIA_INTENSA");
        ClimateAlert scheduledHurricane = visibleAlert("programada-huracan", ClimateAlert.Status.PROGRAMADA,
                ClimateAlert.Severity.BAJA);
        scheduledHurricane.setPhenomenon("HURACAN");
        when(repository.findByStatusIn(List.of(ClimateAlert.Status.PROGRAMADA, ClimateAlert.Status.ACTIVA)))
                .thenReturn(List.of(activeLow, scheduledHigh, scheduledLow, scheduledRain, scheduledHurricane));

        var visible = service.visibleAlerts();

        assertThat(visible).extracting(com.citypass.movilidad.dto.ClimateAlertResponse::alertaId)
                .containsExactly("activa-baja", "programada-alta", "programada-huracan");
        assertThat(visible.get(1).accion()).isEqualTo("BLOQUEO");
        assertThat(visible.get(1).bloqueaAlquiler()).isTrue();
        assertThat(visible.get(2).accion()).isEqualTo("CONFIRMACION");
        assertThat(visible.get(2).bloqueaAlquiler()).isFalse();
    }

    @Test
    void currentBlockingAlertOpensBiAffectationsAndPublishesApplied() throws Exception {
        var data = validEvent();
        data.put("fenomeno", "TORMENTA_FUERTE");
        data.put("nivelCriticidad", "ALTA");

        service.confirm(data, Instant.now());

        verify(affectedStations).blockForClimateAlert(any(ClimateAlert.class), anyList(), any(Instant.class));
        verify(effects).applied(any(ClimateAlert.class), any(Instant.class));
    }

    @Test
    void futureBlockingAlertOpensPreventiveAffectationsWithoutPublishingApplied() throws Exception {
        var data = validEvent();
        data.put("fenomeno", "TORMENTA_FUERTE");
        data.put("nivelCriticidad", "ALTA");
        data.put("vigenciaDesde", Instant.now().plusSeconds(1800).toEpochMilli());

        service.confirm(data, Instant.now());

        verify(affectedStations).blockForClimateAlert(any(ClimateAlert.class), anyList(), any(Instant.class));
        verifyNoInteractions(effects);
    }

    @ParameterizedTest
    @CsvSource({"TORMENTA_ELECTRICA,MEDIA,true", "LLUVIA_INTENSA,ALTA,false", "OLA_DE_FRIO,CRITICA,false"})
    void biAffectationsDependOnTheMatrixNotOnTheSeverity(String phenomenon, String severity, boolean blocks)
            throws Exception {
        var data = validEvent();
        data.put("fenomeno", phenomenon);
        data.put("nivelCriticidad", severity);

        service.confirm(data, Instant.now());

        verify(affectedStations, blocks ? org.mockito.Mockito.times(1) : never())
                .blockForClimateAlert(any(), anyList(), any());
        verify(effects).applied(any(ClimateAlert.class), any(Instant.class));
    }

    @Test
    void tripNoticesBelongToTheRequestedUserAndKeepTheRealSuggestedStation() {
        var alert = visibleAlert("critica", ClimateAlert.Status.ACTIVA, ClimateAlert.Severity.CRITICA);
        var trip = new com.citypass.movilidad.model.Trip();
        trip.setId(42L);
        var station = new com.citypass.movilidad.model.Station();
        station.setId(8L);
        station.setName("Estación segura");
        var notice = new com.citypass.movilidad.model.ClimateTripNotification();
        notice.setTrip(trip);
        notice.setAlert(alert);
        notice.setSuggestedStation(station);
        when(notifications.findByTrip_User_IdAndTrip_StatusAndAlert_StatusOrderByNotifiedAtDesc(
                5L, com.citypass.movilidad.model.enums.TripStatus.ACTIVE, ClimateAlert.Status.ACTIVA))
                .thenReturn(List.of(notice));

        var response = service.tripNotifications(5L);

        assertThat(response).singleElement().satisfies(value -> {
            assertThat(value.tripId()).isEqualTo(42L);
            assertThat(value.suggestedStationId()).isEqualTo(8L);
            assertThat(value.suggestedStationName()).isEqualTo("Estación segura");
            assertThat(value.alerta().alertaId()).isEqualTo("critica");
        });
    }

    private com.fasterxml.jackson.databind.node.ObjectNode validEvent() {
        var data = mapper.createObjectNode();
        data.put("alertaId", "weather-1");
        data.put("nivelCriticidad", "MEDIA");
        data.put("fenomeno", "VIENTO_FUERTE");
        data.put("vigenciaDesde", Instant.now().minusSeconds(60).toEpochMilli());
        data.put("vigenciaHasta", Instant.now().plusSeconds(3600).toEpochMilli());
        data.putArray("barrios");
        return data;
    }

    private static ClimateAlert visibleAlert(String id, ClimateAlert.Status status, ClimateAlert.Severity severity) {
        ClimateAlert alert = new ClimateAlert();
        alert.setExternalAlertId(id);
        alert.setStatus(status);
        alert.setSeverity(severity);
        alert.setValidUntil(Instant.now().plusSeconds(3600));
        return alert;
    }

    @Test
    void confirmedEventNormalizesNeighborhoodAndActivatesWhenAlreadyDue() throws Exception {
        Neighborhood palermo = new Neighborhood();
        palermo.setId("AR-CABA-PALERMO");
        palermo.setName("Palermo");
        when(neighborhoods.findByNormalizedName("PALERMO")).thenReturn(Optional.of(palermo));
        long from = Instant.now().minusSeconds(60).toEpochMilli();
        long until = Instant.now().plusSeconds(3600).toEpochMilli();
        var data = mapper.readTree("""
                {"alertaId":"weather-1","nivelCriticidad":"MEDIA","fenomeno":"VIENTO_FUERTE",
                "barrios":["palérmo"],"vigenciaDesde":%d,"vigenciaHasta":%d}
                """.formatted(from, until));

        service.confirm(data, Instant.now());

        ArgumentCaptor<ClimateAlert> saved = ArgumentCaptor.forClass(ClimateAlert.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getExternalAlertId()).isEqualTo("weather-1");
        assertThat(saved.getValue().getSeverity()).isEqualTo(ClimateAlert.Severity.MEDIA);
        assertThat(saved.getValue().getNeighborhoods()).containsExactly(palermo);
        assertThat(saved.getValue().getStatus()).isEqualTo(ClimateAlert.Status.ACTIVA);
        verify(effects).applied(any(ClimateAlert.class), any(Instant.class));
    }

    @Test
    void futureAlertIsScheduledAndDuplicateBusinessIdIsIgnored() throws Exception {
        long from = Instant.now().plusSeconds(3600).toEpochMilli();
        long until = Instant.now().plusSeconds(7200).toEpochMilli();
        var data = mapper.readTree("""
                {"alertaId":"weather-2","nivelCriticidad":"ALTA","fenomeno":"GRANIZO",
                "barrios":[],"vigenciaDesde":%d,"vigenciaHasta":%d}
                """.formatted(from, until));

        service.confirm(data, Instant.now());
        ArgumentCaptor<ClimateAlert> saved = ArgumentCaptor.forClass(ClimateAlert.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ClimateAlert.Status.PROGRAMADA);
        verify(effects, never()).applied(any(), any());

        when(repository.findByExternalAlertId("weather-2")).thenReturn(Optional.of(saved.getValue()));
        service.confirm(data, Instant.now());
        verify(repository).saveAndFlush(any());
        verify(affectedStations).blockForClimateAlert(eq(saved.getValue()), anyList(), any());
    }

    @Test
    void scheduledAlertActivatesAndActiveAlertCanBeFinalized() throws Exception {
        ClimateAlert alert = new ClimateAlert();
        alert.setExternalAlertId("weather-3");
        alert.setStatus(ClimateAlert.Status.PROGRAMADA);
        alert.setId(3L);
        alert.setPhenomenon("TORMENTA_FUERTE");
        alert.setSeverity(ClimateAlert.Severity.ALTA);
        alert.setValidFrom(Instant.now().minusSeconds(30));
        alert.setValidUntil(Instant.now().plusSeconds(3600));
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(alert));

        service.activateAlert(3L);

        assertThat(alert.getStatus()).isEqualTo(ClimateAlert.Status.ACTIVA);
        verify(effects).applied(any(ClimateAlert.class), any(Instant.class));
        // Se vuelve a pedir el bloqueo (idempotente) para cubrir estaciones que pasaron a ACTIVE.
        verify(affectedStations).blockForClimateAlert(eq(alert), anyList(), any(Instant.class));

        when(repository.findByExternalAlertId("weather-3")).thenReturn(Optional.of(alert));
        service.finish(mapper.readTree("""
                {"alertaId":"weather-3","finalizadaEn":1794011463871}
                """));

        assertThat(alert.getStatus()).isEqualTo(ClimateAlert.Status.FINALIZADA);
        assertThat(alert.getFinalizationReason()).isEqualTo(ClimateAlert.FinalizationReason.ESPACIOS);
        verify(effects).lifted(alert, Instant.ofEpochMilli(1794011463871L));
        verify(affectedStations).restoreClimateAlert("weather-3", Instant.ofEpochMilli(1794011463871L));
    }

    @Test
    void expiredActiveAlertIsFinalizedAtItsValidUntilAndLifted() {
        Instant validUntil = Instant.now().minusSeconds(5);
        ClimateAlert alert = alert(10L, "weather-10", ClimateAlert.Status.ACTIVA, validUntil);
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(alert));

        service.expireAlert(10L);

        assertThat(alert.getStatus()).isEqualTo(ClimateAlert.Status.FINALIZADA);
        assertThat(alert.getFinalizationReason()).isEqualTo(ClimateAlert.FinalizationReason.VENCIMIENTO);
        // La fecha de cierre es el fin real de la vigencia, no el momento en que corrió el job.
        assertThat(alert.getFinalizedAt()).isEqualTo(validUntil);
        verify(repository).save(alert);
        verify(affectedStations).restoreClimateAlert("weather-10", validUntil);
        verify(effects).lifted(alert, validUntil);
    }

    @Test
    void expiredScheduledAlertRestoresPreventiveAffectationsWithoutLifting() {
        Instant validUntil = Instant.now().minusSeconds(5);
        ClimateAlert alert = alert(11L, "weather-11", ClimateAlert.Status.PROGRAMADA, validUntil);
        when(repository.findByIdForUpdate(11L)).thenReturn(Optional.of(alert));

        service.expireAlert(11L);

        assertThat(alert.getStatus()).isEqualTo(ClimateAlert.Status.FINALIZADA);
        assertThat(alert.getFinalizationReason()).isEqualTo(ClimateAlert.FinalizationReason.VENCIMIENTO);
        verify(affectedStations).restoreClimateAlert("weather-11", validUntil);
        verifyNoInteractions(effects);
    }

    @Test
    void alertStillInForceIsNotExpired() {
        ClimateAlert alert = alert(12L, "weather-12", ClimateAlert.Status.ACTIVA, Instant.now().plusSeconds(600));
        when(repository.findByIdForUpdate(12L)).thenReturn(Optional.of(alert));

        service.expireAlert(12L);
        service.expireAlert(99L);

        assertThat(alert.getStatus()).isEqualTo(ClimateAlert.Status.ACTIVA);
        verify(repository, never()).save(any());
        verifyNoInteractions(effects, affectedStations);
    }

    @Test
    void alertAlreadyFinalizedByEspaciosIsNotExpiredAgain() {
        ClimateAlert alert = alert(13L, "weather-13", ClimateAlert.Status.FINALIZADA, Instant.now().minusSeconds(5));
        alert.setFinalizationReason(ClimateAlert.FinalizationReason.ESPACIOS);
        when(repository.findByIdForUpdate(13L)).thenReturn(Optional.of(alert));

        service.expireAlert(13L);

        assertThat(alert.getFinalizationReason()).isEqualTo(ClimateAlert.FinalizationReason.ESPACIOS);
        verify(repository, never()).save(any());
        verifyNoInteractions(effects, affectedStations);
    }

    @Test
    void espaciosFinishAfterExpirationIsIgnoredAndKeepsTheReason() throws Exception {
        Instant validUntil = Instant.now().minusSeconds(5);
        ClimateAlert alert = alert(14L, "weather-14", ClimateAlert.Status.ACTIVA, validUntil);
        when(repository.findByIdForUpdate(14L)).thenReturn(Optional.of(alert));
        when(repository.findByExternalAlertId("weather-14")).thenReturn(Optional.of(alert));
        service.expireAlert(14L);

        service.finish(mapper.readTree("{\"alertaId\":\"weather-14\",\"finalizadaEn\":"
                + Instant.now().toEpochMilli() + "}"));

        assertThat(alert.getFinalizationReason()).isEqualTo(ClimateAlert.FinalizationReason.VENCIMIENTO);
        assertThat(alert.getFinalizedAt()).isEqualTo(validUntil);
        verify(repository, org.mockito.Mockito.times(1)).save(alert);
        verify(affectedStations, org.mockito.Mockito.times(1)).restoreClimateAlert(any(), any());
        verify(effects, org.mockito.Mockito.times(1)).lifted(any(), any());
    }

    @Test
    void espaciosFinishAfterValidUntilIsRespectedWhenItArrivesFirst() throws Exception {
        Instant validUntil = Instant.now().minusSeconds(60);
        Instant finishedAt = Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        ClimateAlert alert = alert(15L, "weather-15", ClimateAlert.Status.ACTIVA, validUntil);
        when(repository.findByExternalAlertId("weather-15")).thenReturn(Optional.of(alert));

        service.finish(mapper.readTree("{\"alertaId\":\"weather-15\",\"finalizadaEn\":"
                + finishedAt.toEpochMilli() + "}"));

        assertThat(alert.getFinalizationReason()).isEqualTo(ClimateAlert.FinalizationReason.ESPACIOS);
        assertThat(alert.getFinalizedAt()).isEqualTo(finishedAt);
        verify(effects).lifted(alert, finishedAt);
    }

    @Test
    void alertReceivedAlreadyExpiredIsStoredFinalizedWithoutEffectsAndDuplicateIsIgnored() throws Exception {
        Neighborhood palermo = new Neighborhood();
        palermo.setId("AR-CABA-PALERMO");
        when(neighborhoods.findByNormalizedName("PALERMO")).thenReturn(Optional.of(palermo));
        long from = Instant.now().minusSeconds(7200).toEpochMilli();
        long until = Instant.now().minusSeconds(60).toEpochMilli();
        var data = mapper.readTree("""
                {"alertaId":"weather-16","nivelCriticidad":"ALTA","fenomeno":"TORMENTA_ELECTRICA",
                "barrios":["Palermo"],"vigenciaDesde":%d,"vigenciaHasta":%d}
                """.formatted(from, until));

        service.confirm(data, Instant.now());

        ArgumentCaptor<ClimateAlert> saved = ArgumentCaptor.forClass(ClimateAlert.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ClimateAlert.Status.FINALIZADA);
        assertThat(saved.getValue().getFinalizationReason()).isEqualTo(ClimateAlert.FinalizationReason.VENCIMIENTO);
        assertThat(saved.getValue().getFinalizedAt()).isEqualTo(Instant.ofEpochMilli(until));
        assertThat(saved.getValue().getActivatedAt()).isNull();
        assertThat(saved.getValue().getNeighborhoods()).containsExactly(palermo);
        verifyNoInteractions(effects, affectedStations);

        when(repository.findByExternalAlertId("weather-16")).thenReturn(Optional.of(saved.getValue()));
        service.confirm(data, Instant.now());
        verify(repository).saveAndFlush(any());
        verifyNoInteractions(effects, affectedStations);
    }

    @Test
    void scheduledAlertWhoseValidityAlreadyEndedIsNotActivated() {
        ClimateAlert alert = alert(17L, "weather-17", ClimateAlert.Status.PROGRAMADA, Instant.now().minusSeconds(5));
        alert.setValidFrom(Instant.now().minusSeconds(3600));
        when(repository.findByIdForUpdate(17L)).thenReturn(Optional.of(alert));

        service.activateAlert(17L);

        assertThat(alert.getStatus()).isEqualTo(ClimateAlert.Status.PROGRAMADA);
        verify(repository, never()).save(any());
        verifyNoInteractions(effects, affectedStations);
    }

    @Test
    void expiredAlertsAreNoLongerVisibleNorNotifiedBeforeTheJobRuns() {
        ClimateAlert expiredActive = visibleAlert("vencida-activa", ClimateAlert.Status.ACTIVA, ClimateAlert.Severity.ALTA);
        expiredActive.setValidUntil(Instant.now().minusSeconds(1));
        ClimateAlert expiredScheduled = visibleAlert("vencida-programada", ClimateAlert.Status.PROGRAMADA,
                ClimateAlert.Severity.ALTA);
        expiredScheduled.setValidUntil(Instant.now().minusSeconds(1));
        ClimateAlert current = visibleAlert("vigente", ClimateAlert.Status.ACTIVA, ClimateAlert.Severity.ALTA);
        when(repository.findByStatusIn(List.of(ClimateAlert.Status.PROGRAMADA, ClimateAlert.Status.ACTIVA)))
                .thenReturn(List.of(expiredActive, expiredScheduled, current));
        var notice = new com.citypass.movilidad.model.ClimateTripNotification();
        notice.setTrip(new com.citypass.movilidad.model.Trip());
        notice.setAlert(expiredActive);
        when(notifications.findByTrip_User_IdAndTrip_StatusAndAlert_StatusOrderByNotifiedAtDesc(
                5L, com.citypass.movilidad.model.enums.TripStatus.ACTIVE, ClimateAlert.Status.ACTIVA))
                .thenReturn(List.of(notice));

        assertThat(service.visibleAlerts()).extracting(com.citypass.movilidad.dto.ClimateAlertResponse::alertaId)
                .containsExactly("vigente");
        assertThat(service.tripNotifications(5L)).isEmpty();
    }

    private static ClimateAlert alert(Long id, String externalId, ClimateAlert.Status status, Instant validUntil) {
        ClimateAlert alert = new ClimateAlert();
        alert.setId(id);
        alert.setExternalAlertId(externalId);
        alert.setStatus(status);
        alert.setPhenomenon("TORMENTA_ELECTRICA");
        alert.setSeverity(ClimateAlert.Severity.ALTA);
        alert.setValidFrom(validUntil.minusSeconds(3600));
        alert.setValidUntil(validUntil);
        return alert;
    }
}
