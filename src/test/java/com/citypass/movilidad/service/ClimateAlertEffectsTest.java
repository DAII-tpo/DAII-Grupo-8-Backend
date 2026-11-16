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
import static org.mockito.Mockito.when;

class ClimateAlertEffectsTest {
    private final StationRepository stations = mock(StationRepository.class);
    private final TripRepository trips = mock(TripRepository.class);
    private final ClimateTripNotificationRepository notifications = mock(ClimateTripNotificationRepository.class);
    private final ClimatePolicyService policy = mock(ClimatePolicyService.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final StationAvailabilityService availability = mock(StationAvailabilityService.class);
    private final ClimateAlertEffects service = new ClimateAlertEffects(stations, trips, notifications,
            policy, events, availability);
    private final ObjectMapper mapper = new ObjectMapper();

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
        when(policy.effectiveAlert(station)).thenReturn(Optional.of(overlapping));

        service.lifted(alert, Instant.now());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("AlertaClimaticaLevantada"), eq(AggregateType.CLIMATE_ALERT), eq(7L),
                payload.capture());
        assertThat(mapper.valueToTree(payload.getValue()).path("estacionesRestablecidas").size()).isZero();
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
