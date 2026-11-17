package com.citypass.movilidad.service;

import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.model.ClimateTripNotification;
import com.citypass.movilidad.model.Neighborhood;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.model.enums.TripStatus;
import com.citypass.movilidad.repository.ClimateTripNotificationRepository;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.TripRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClimateAlertEffectsTest {
    private final StationRepository stations = mock(StationRepository.class);
    private final TripRepository trips = mock(TripRepository.class);
    private final ClimateTripNotificationRepository notifications = mock(ClimateTripNotificationRepository.class);
    private final ClimatePolicyService policy = mock(ClimatePolicyService.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final StationAvailabilityService availability = mock(StationAvailabilityService.class);
    private final ClimateAlertEffects service = new ClimateAlertEffects(stations, trips, notifications,
            policy, events, availability, new ClimateAffectedStations(stations));
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void policyAnswersWithTheRealMatrix() {
        ClimatePolicyMatrix matrix = new ClimatePolicyMatrix();
        when(policy.effectiveOf(any())).thenAnswer(invocation -> {
            ClimateAlert alert = invocation.getArgument(0);
            return new ClimatePolicyService.EffectiveClimate(alert, matrix.responseFor(alert));
        });
    }

    @Test
    void highAlertPublishesOnlyActiveBlockedStations() {
        ClimateAlert alert = alert(ClimateAlert.Severity.ALTA);
        Station active = station(1L, "ecobici-1-1", "AR-CABA-PALERMO", StationStatus.ACTIVE);
        Station inactive = station(2L, "ecobici-2-2", "AR-CABA-PALERMO", StationStatus.INACTIVE);
        when(stations.findByNeighborhood_IdInAndDeletedAtIsNull(any())).thenReturn(List.of(active, inactive));

        service.applied(alert, Instant.parse("2026-11-12T10:00:00Z"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("AlertaClimaticaAplicada"), eq(AggregateType.CLIMATE_ALERT), eq(7L),
                payload.capture());
        var json = mapper.valueToTree(payload.getValue());
        assertThat(json.path("cantidadEstacionesBloqueadas").asInt()).isEqualTo(1);
        assertThat(json.path("estacionesBloqueadas").get(0).path("id").asText()).isEqualTo("ecobici-1-1");
        assertThat(json.path("accionesAplicadas").toString()).contains("BLOQUEO_ALQUILER");
    }

    @Test
    void criticalAlertStoresTripNoticeWithNearestReturnableStation() {
        ClimateAlert alert = alert(ClimateAlert.Severity.CRITICA);
        Station origin = station(1L, "ecobici-1-1", "AR-CABA-PALERMO", StationStatus.ACTIVE);
        Station safe = station(2L, "ecobici-2-2", "AR-CABA-RETIRO", StationStatus.ACTIVE);
        Trip trip = new Trip();
        trip.setId(9L);
        trip.setOriginStation(origin);
        when(stations.findByNeighborhood_IdInAndDeletedAtIsNull(any())).thenReturn(List.of(origin));
        when(stations.findByStatusAndDeletedAtIsNull(StationStatus.ACTIVE)).thenReturn(List.of(origin, safe));
        when(availability.availabilityFor(Map.of(1L, 20, 2L, 20)))
                .thenReturn(Map.of(1L, new StationAvailability(0, 0),
                        2L, new StationAvailability(5, 15)));
        when(trips.findByStatusAndOriginStation_IdIn(TripStatus.ACTIVE, List.of(1L))).thenReturn(List.of(trip));

        service.applied(alert, Instant.now());

        ArgumentCaptor<ClimateTripNotification> saved = ArgumentCaptor.forClass(ClimateTripNotification.class);
        verify(notifications).save(saved.capture());
        assertThat(saved.getValue().getTrip()).isSameAs(trip);
        assertThat(saved.getValue().getSuggestedStation()).isSameAs(safe);
    }

    @Test
    void liftingDoesNotClaimStationRestoredWhileAnotherHighAlertIsActive() {
        ClimateAlert alert = alert(ClimateAlert.Severity.ALTA);
        Station station = station(1L, "ecobici-1-1", "AR-CABA-PALERMO", StationStatus.ACTIVE);
        ClimateAlert overlapping = alert(ClimateAlert.Severity.CRITICA);
        when(stations.findByNeighborhood_IdInAndDeletedAtIsNull(any())).thenReturn(List.of(station));
        when(policy.effective(station)).thenReturn(Optional.of(
                new ClimatePolicyService.EffectiveClimate(overlapping, ClimateResponse.BLOQUEO_Y_AVISO_VIAJES)));

        service.lifted(alert, Instant.now());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("AlertaClimaticaLevantada"), eq(AggregateType.CLIMATE_ALERT), eq(7L),
                payload.capture());
        assertThat(mapper.valueToTree(payload.getValue()).path("estacionesRestablecidas").size()).isZero();
    }

    @Test
    void liftingRestoresStationWhenTheRemainingAlertOnlyWarns() {
        ClimateAlert alert = alert(ClimateAlert.Severity.ALTA);
        Station station = station(1L, "ecobici-1-1", "AR-CABA-PALERMO", StationStatus.ACTIVE);
        ClimateAlert rain = alert(ClimateAlert.Severity.ALTA);
        rain.setPhenomenon("LLUVIA_INTENSA");
        when(stations.findByNeighborhood_IdInAndDeletedAtIsNull(any())).thenReturn(List.of(station));
        when(policy.effective(station)).thenReturn(Optional.of(
                new ClimatePolicyService.EffectiveClimate(rain, ClimateResponse.CONFIRMACION)));

        service.lifted(alert, Instant.now());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("AlertaClimaticaLevantada"), eq(AggregateType.CLIMATE_ALERT), eq(7L),
                payload.capture());
        assertThat(mapper.valueToTree(payload.getValue()).path("estacionesRestablecidas").size()).isEqualTo(1);
    }

    @Test
    void matrixDecidesBlockingAndActionsInsteadOfTheSeverity() {
        // Tormenta eléctrica MEDIA bloquea; lluvia intensa ALTA solo pide confirmación.
        ClimateAlert storm = alert(ClimateAlert.Severity.MEDIA);
        storm.setPhenomenon("TORMENTA_ELECTRICA");
        Station active = station(1L, "ecobici-1-1", "AR-CABA-PALERMO", StationStatus.ACTIVE);
        when(stations.findByNeighborhood_IdInAndDeletedAtIsNull(any())).thenReturn(List.of(active));

        service.applied(storm, Instant.now());
        ClimateAlert rain = alert(ClimateAlert.Severity.ALTA);
        rain.setPhenomenon("LLUVIA_INTENSA");
        service.applied(rain, Instant.now());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.times(2)).publish(eq("AlertaClimaticaAplicada"),
                eq(AggregateType.CLIMATE_ALERT), eq(7L), payload.capture());
        var stormJson = mapper.valueToTree(payload.getAllValues().get(0));
        assertThat(stormJson.path("cantidadEstacionesBloqueadas").asInt()).isEqualTo(1);
        assertThat(stormJson.path("accionesAplicadas").toString())
                .isEqualTo("[\"AVISO_USUARIOS\",\"BLOQUEO_ALQUILER\"]");
        var rainJson = mapper.valueToTree(payload.getAllValues().get(1));
        assertThat(rainJson.path("cantidadEstacionesBloqueadas").asInt()).isZero();
        assertThat(rainJson.path("accionesAplicadas").toString())
                .isEqualTo("[\"AVISO_USUARIOS\",\"CONFIRMACION_RIESGO_AL_ALQUILAR\"]");
        verifyNoInteractions(trips, notifications);
    }

    @Test
    void criticalAlertDoesNotSuggestAnAffectedStationWhenNoSafeOneHasRoom() {
        ClimateAlert alert = alert(ClimateAlert.Severity.CRITICA);
        Station origin = station(1L, "ecobici-1-1", "AR-CABA-PALERMO", StationStatus.ACTIVE);
        Station sameArea = station(2L, "ecobici-2-2", "AR-CABA-PALERMO", StationStatus.ACTIVE);
        Trip trip = new Trip();
        trip.setId(9L);
        trip.setOriginStation(origin);
        when(stations.findByNeighborhood_IdInAndDeletedAtIsNull(any())).thenReturn(List.of(origin, sameArea));
        when(stations.findByStatusAndDeletedAtIsNull(StationStatus.ACTIVE)).thenReturn(List.of(origin, sameArea));
        when(availability.availabilityFor(Map.of(1L, 20, 2L, 20)))
                .thenReturn(Map.of(1L, new StationAvailability(0, 20), 2L, new StationAvailability(5, 15)));
        when(trips.findByStatusAndOriginStation_IdIn(TripStatus.ACTIVE, List.of(1L, 2L))).thenReturn(List.of(trip));

        service.applied(alert, Instant.now());

        ArgumentCaptor<ClimateTripNotification> saved = ArgumentCaptor.forClass(ClimateTripNotification.class);
        verify(notifications).save(saved.capture());
        assertThat(saved.getValue().getSuggestedStation()).isNull();
    }

    @Test
    void failedActivationPublishesFailureWithoutClaimingAppliedActions() {
        ClimateAlert alert = alert(ClimateAlert.Severity.ALTA);
        service.failed(alert, Instant.parse("2026-11-12T10:00:00Z"), "No se pudo aplicar; se reintentará");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("AlertaClimaticaAplicada"), eq(AggregateType.CLIMATE_ALERT), eq(7L),
                payload.capture());
        var json = mapper.valueToTree(payload.getValue());
        assertThat(json.path("estado").asText()).isEqualTo("FALLIDA");
        assertThat(json.path("accionesAplicadas").isEmpty()).isTrue();
        assertThat(json.path("estacionesBloqueadas").isEmpty()).isTrue();
        assertThat(json.path("detalleError").asText()).contains("reintentará");
    }

    private static ClimateAlert alert(ClimateAlert.Severity severity) {
        Neighborhood neighborhood = new Neighborhood();
        neighborhood.setId("AR-CABA-PALERMO");
        ClimateAlert alert = new ClimateAlert();
        alert.setId(7L);
        alert.setExternalAlertId("weather-7");
        alert.setPhenomenon("TORMENTA_FUERTE");
        alert.setSeverity(severity);
        alert.getNeighborhoods().add(neighborhood);
        return alert;
    }

    private static Station station(Long id, String externalId, String neighborhoodId, StationStatus status) {
        Neighborhood neighborhood = new Neighborhood();
        neighborhood.setId(neighborhoodId);
        Station station = new Station();
        station.setId(id);
        station.setExternalId(externalId);
        station.setName("Estación " + id);
        station.setNeighborhood(neighborhood);
        station.setStatus(status);
        station.setCapacity(20);
        station.setLatitude(new BigDecimal("-34.5900000"));
        station.setLongitude(new BigDecimal("-58.3900000"));
        return station;
    }
}
