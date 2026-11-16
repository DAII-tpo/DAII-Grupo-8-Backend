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
    private final ClimatePolicyService service = new ClimatePolicyService(repository, stations);

    @Test
    void ignoresMissingStation() {
        assertThat(service.effectiveAlert(null)).isEmpty();
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

    private static Neighborhood neighborhood(String id, String name) {
        Neighborhood neighborhood = new Neighborhood();
        neighborhood.setId(id);
        neighborhood.setName(name);
        return neighborhood;
    }

    private static ClimateAlert alert(String id, ClimateAlert.Severity severity, Neighborhood neighborhood) {
        ClimateAlert alert = new ClimateAlert();
        alert.setExternalAlertId(id);
        alert.setSeverity(severity);
        alert.setStatus(ClimateAlert.Status.ACTIVA);
        alert.getNeighborhoods().add(neighborhood);
        return alert;
    }

    @Test
    void ignoresStationWithoutVerifiedNeighborhood() {
        assertThat(service.effectiveAlert(new Station())).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void choosesHighestActiveSeverityForStationNeighborhood() {
        Neighborhood neighborhood = new Neighborhood();
        neighborhood.setId("AR-CABA-PALERMO");
        Station station = new Station();
        station.setNeighborhood(neighborhood);
        ClimateAlert low = new ClimateAlert();
        low.setSeverity(ClimateAlert.Severity.BAJA);
        ClimateAlert high = new ClimateAlert();
        high.setSeverity(ClimateAlert.Severity.ALTA);
        when(repository.findByStatusAndNeighborhoodId(ClimateAlert.Status.ACTIVA, neighborhood.getId()))
                .thenReturn(List.of(high, low));

        assertThat(service.effectiveAlert(station)).containsSame(high);
    }
}
