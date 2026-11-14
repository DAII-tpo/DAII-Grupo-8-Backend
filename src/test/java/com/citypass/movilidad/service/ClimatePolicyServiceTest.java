package com.citypass.movilidad.service;

import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.model.Neighborhood;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.repository.ClimateAlertRepository;
import com.citypass.movilidad.repository.StationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClimatePolicyServiceTest {
    private final ClimateAlertRepository repository = mock(ClimateAlertRepository.class);
    private final ClimatePolicyService service = new ClimatePolicyService(repository, mock(StationRepository.class));

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
