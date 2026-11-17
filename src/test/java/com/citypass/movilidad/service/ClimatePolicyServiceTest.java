package com.citypass.movilidad.service;

import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.model.Neighborhood;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.repository.ClimateAlertRepository;
import com.citypass.movilidad.repository.StationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClimatePolicyServiceTest {
    private final ClimateAlertRepository repository = mock(ClimateAlertRepository.class);
    private final StationRepository stations = mock(StationRepository.class);
    private final ClimatePolicyService service = new ClimatePolicyService(repository, stations,
            new ClimatePolicyMatrix());

    @Test
    void ignoresMissingStation() {
        assertThat(service.effective(null)).isEmpty();
        verifyNoInteractions(repository, stations);
    }

    @Test
    void stationWithoutAlertReturnsItsIdAndNoClimateWarning() {
        Station station = new Station();
        station.setId(5L);

        var response = service.stationStatus(station);

        assertThat(response.stationId()).isEqualTo(5L);
        assertThat(response.alerta()).isNull();
        verifyNoInteractions(repository, stations);
    }

    @Test
    void noActiveAlertsReturnsNoAffectedStationsWithoutQueryingTheCatalog() {
        when(repository.findByStatus(ClimateAlert.Status.ACTIVA)).thenReturn(List.of());

        assertThat(service.affectedStationStatuses()).isEmpty();

        verifyNoInteractions(stations);
    }

    @Test
    void affectedStationsUseTheStrongestAlertPerNeighborhoodInEitherArrivalOrder() {
        Neighborhood palermo = neighborhood("AR-CABA-PALERMO", "Palermo");
        Neighborhood retiro = neighborhood("AR-CABA-RETIRO", "Retiro");
        ClimateAlert high = alert("alta", ClimateAlert.Severity.ALTA, palermo);
        ClimateAlert low = alert("baja", ClimateAlert.Severity.BAJA, palermo);
        ClimateAlert critical = alert("critica", ClimateAlert.Severity.CRITICA, palermo);
        ClimateAlert medium = alert("media", ClimateAlert.Severity.MEDIA, retiro);
        Station first = new Station();
        first.setId(1L);
        first.setNeighborhood(palermo);
        Station second = new Station();
        second.setId(2L);
        second.setNeighborhood(retiro);
        when(repository.findByStatus(ClimateAlert.Status.ACTIVA)).thenReturn(List.of(high, low, critical, medium));
        when(stations.findByNeighborhood_IdInAndDeletedAtIsNull(Set.of(palermo.getId(), retiro.getId())))
                .thenReturn(List.of(first, second));
        when(repository.findByStatusAndNeighborhoodId(ClimateAlert.Status.ACTIVA, palermo.getId()))
                .thenReturn(List.of(high, low, critical));

        var responses = service.affectedStationStatuses();

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).stationId()).isEqualTo(1L);
        assertThat(responses.get(0).alerta().alertaId()).isEqualTo("critica");
        assertThat(responses.get(1).stationId()).isEqualTo(2L);
        assertThat(responses.get(1).alerta().nivelCriticidad()).isEqualTo(ClimateAlert.Severity.MEDIA);
        assertThat(service.stationStatus(first).alerta().alertaId()).isEqualTo("critica");
    }

    @Test
    void mostRestrictiveResponseWinsOverHigherSeverityInEitherArrivalOrder() {
        Neighborhood palermo = neighborhood("AR-CABA-PALERMO", "Palermo");
        Station station = new Station();
        station.setId(1L);
        station.setNeighborhood(palermo);
        ClimateAlert storm = alert("tormenta", "TORMENTA_ELECTRICA", ClimateAlert.Severity.MEDIA, palermo);
        ClimateAlert rain = alert("lluvia", "LLUVIA_INTENSA", ClimateAlert.Severity.ALTA, palermo);

        for (List<ClimateAlert> order : List.of(List.of(storm, rain), List.of(rain, storm))) {
            when(repository.findByStatusAndNeighborhoodId(ClimateAlert.Status.ACTIVA, palermo.getId()))
                    .thenReturn(order);
            when(repository.findByStatus(ClimateAlert.Status.ACTIVA)).thenReturn(order);
            when(stations.findByNeighborhood_IdInAndDeletedAtIsNull(Set.of(palermo.getId())))
                    .thenReturn(List.of(station));

            assertThat(service.effective(station)).hasValueSatisfying(effective -> {
                assertThat(effective.alert()).isSameAs(storm);
                assertThat(effective.response()).isEqualTo(ClimateResponse.BLOQUEO);
            });
            var status = service.affectedStationStatuses().getFirst().alerta();
            assertThat(status.alertaId()).isEqualTo("tormenta");
            assertThat(status.accion()).isEqualTo("BLOQUEO");
            assertThat(status.bloqueaAlquiler()).isTrue();
        }
    }

    @Test
    void sameResponseIsDecidedBySeverityThenByOldestValidity() {
        Neighborhood palermo = neighborhood("AR-CABA-PALERMO", "Palermo");
        Station station = new Station();
        station.setNeighborhood(palermo);
        // Granizo ALTA y tormenta eléctrica CRITICA dan BLOQUEO_Y_AVISO_VIAJES: gana la de mayor criticidad.
        ClimateAlert hail = alert("granizo", "GRANIZO", ClimateAlert.Severity.ALTA, palermo);
        ClimateAlert storm = alert("tormenta", "TORMENTA_ELECTRICA", ClimateAlert.Severity.CRITICA, palermo);
        when(repository.findByStatusAndNeighborhoodId(ClimateAlert.Status.ACTIVA, palermo.getId()))
                .thenReturn(List.of(storm, hail));
        assertThat(service.effective(station).map(effective -> effective.alert())).containsSame(storm);

        // Mismo nivel y criticidad: gana la vigente desde antes.
        ClimateAlert older = alert("vieja", "GRANIZO", ClimateAlert.Severity.ALTA, palermo);
        older.setValidFrom(java.time.Instant.parse("2026-11-15T10:00:00Z"));
        ClimateAlert newer = alert("nueva", "GRANIZO", ClimateAlert.Severity.ALTA, palermo);
        newer.setValidFrom(java.time.Instant.parse("2026-11-15T11:00:00Z"));
        when(repository.findByStatusAndNeighborhoodId(ClimateAlert.Status.ACTIVA, palermo.getId()))
                .thenReturn(List.of(newer, older));
        assertThat(service.effective(station).map(effective -> effective.alert())).containsSame(older);
    }

    private static Neighborhood neighborhood(String id, String name) {
        Neighborhood neighborhood = new Neighborhood();
        neighborhood.setId(id);
        neighborhood.setName(name);
        return neighborhood;
    }

    private static ClimateAlert alert(String id, ClimateAlert.Severity severity, Neighborhood neighborhood) {
        return alert(id, "TORMENTA_FUERTE", severity, neighborhood);
    }

    private static ClimateAlert alert(String id, String phenomenon, ClimateAlert.Severity severity,
                                      Neighborhood neighborhood) {
        ClimateAlert alert = new ClimateAlert();
        alert.setExternalAlertId(id);
        alert.setPhenomenon(phenomenon);
        alert.setSeverity(severity);
        alert.setStatus(ClimateAlert.Status.ACTIVA);
        alert.setValidUntil(java.time.Instant.now().plusSeconds(3600));
        alert.getNeighborhoods().add(neighborhood);
        return alert;
    }

    @Test
    void activeAlertPastItsValidUntilNoLongerAppliesBeforeTheJobRuns() {
        Neighborhood palermo = neighborhood("AR-CABA-PALERMO", "Palermo");
        Station station = new Station();
        station.setId(1L);
        station.setNeighborhood(palermo);
        ClimateAlert expired = alert("vencida", "TORMENTA_ELECTRICA", ClimateAlert.Severity.CRITICA, palermo);
        expired.setValidUntil(java.time.Instant.now().minusSeconds(1));
        when(repository.findByStatusAndNeighborhoodId(ClimateAlert.Status.ACTIVA, palermo.getId()))
                .thenReturn(List.of(expired));
        when(repository.findByStatus(ClimateAlert.Status.ACTIVA)).thenReturn(List.of(expired));

        assertThat(service.effective(station)).isEmpty();
        assertThat(service.affectedStationStatuses()).isEmpty();
        verifyNoInteractions(stations);
    }

    @Test
    void alertInForceWinsOverAMoreRestrictiveExpiredOneInTheSameNeighborhood() {
        Neighborhood palermo = neighborhood("AR-CABA-PALERMO", "Palermo");
        Station station = new Station();
        station.setId(1L);
        station.setNeighborhood(palermo);
        ClimateAlert expired = alert("vencida", "TORMENTA_ELECTRICA", ClimateAlert.Severity.CRITICA, palermo);
        expired.setValidUntil(java.time.Instant.now().minusSeconds(1));
        ClimateAlert current = alert("vigente", "LLUVIA_INTENSA", ClimateAlert.Severity.MEDIA, palermo);
        when(repository.findByStatusAndNeighborhoodId(ClimateAlert.Status.ACTIVA, palermo.getId()))
                .thenReturn(List.of(expired, current));
        when(repository.findByStatus(ClimateAlert.Status.ACTIVA)).thenReturn(List.of(expired, current));
        when(stations.findByNeighborhood_IdInAndDeletedAtIsNull(Set.of(palermo.getId())))
                .thenReturn(List.of(station));

        assertThat(service.effective(station).map(effective -> effective.alert())).containsSame(current);
        assertThat(service.affectedStationStatuses()).singleElement()
                .satisfies(status -> assertThat(status.alerta().alertaId()).isEqualTo("vigente"));
    }

    @Test
    void ignoresStationWithoutVerifiedNeighborhood() {
        assertThat(service.effective(new Station())).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void choosesHighestActiveSeverityForStationNeighborhood() {
        Neighborhood neighborhood = new Neighborhood();
        neighborhood.setId("AR-CABA-PALERMO");
        Station station = new Station();
        station.setNeighborhood(neighborhood);
        ClimateAlert low = alert("baja", ClimateAlert.Severity.BAJA, neighborhood);
        ClimateAlert high = alert("alta", ClimateAlert.Severity.ALTA, neighborhood);
        when(repository.findByStatusAndNeighborhoodId(ClimateAlert.Status.ACTIVA, neighborhood.getId()))
                .thenReturn(List.of(high, low));

        assertThat(service.effective(station)).hasValueSatisfying(effective -> {
            assertThat(effective.alert()).isSameAs(high);
            assertThat(effective.response()).isEqualTo(ClimateResponse.BLOQUEO);
        });
    }
}
