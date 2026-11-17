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
        scheduled.setStatus(ClimateAlert.Status.PROGRAMADA);
        when(repository.findByExternalAlertId("weather-1")).thenReturn(Optional.of(scheduled));
        Instant finishedAt = Instant.parse("2026-11-14T12:00:00Z");

        service.finish(mapper.readTree("{\"alertaId\":\"weather-1\",\"finalizadaEn\":"
                + finishedAt.toEpochMilli() + "}"));

        assertThat(scheduled.getStatus()).isEqualTo(ClimateAlert.Status.FINALIZADA);
        assertThat(scheduled.getFinalizedAt()).isEqualTo(finishedAt);
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
        verify(effects).lifted(any(ClimateAlert.class), any(Instant.class));
        verify(affectedStations).restoreClimateAlert("weather-3", Instant.ofEpochMilli(1794011463871L));
    }
}
