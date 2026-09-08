package com.citypass.movilidad.mapper;

import com.citypass.movilidad.importer.EcobiciStationRow;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.model.enums.StationStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EcobiciStationMapperTest {

    private final EcobiciStationMapper mapper = new EcobiciStationMapper();

    @Test
    void mapsAValidDatasetRow() {
        EcobiciStationRow row = new EcobiciStationRow(
                "2", "RETIRO I", "Av. Ramos Mejia 1300", "-34.592424", "-58.374710", "20");

        Station station = mapper.toStation(row);

        assertThat(station.getExternalId()).isEqualTo("2");
        assertThat(station.getName()).isEqualTo("RETIRO I");
        assertThat(station.getAddress()).isEqualTo("Av. Ramos Mejia 1300");
        assertThat(station.getLatitude()).isEqualByComparingTo(new BigDecimal("-34.592424"));
        assertThat(station.getLongitude()).isEqualByComparingTo(new BigDecimal("-58.374710"));
        assertThat(station.getCapacity()).isEqualTo(20);
        assertThat(station.getSource()).isEqualTo(StationSource.DATASET);
        assertThat(station.getStatus()).isEqualTo(StationStatus.ACTIVE);
    }

    @Test
    void rejectsRowsOutsideCaba() {
        EcobiciStationRow row = new EcobiciStationRow(
                "592", "SUSTENTACAO", "Sao Paulo", "-23.569064", "-46.697839", "20");

        assertThatThrownBy(() -> mapper.toStation(row))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside CABA");
    }

    @Test
    void rejectsMissingFieldsAndNegativeCapacity() {
        EcobiciStationRow row = new EcobiciStationRow(
                "2", " ", "Address", "-34.592424", "-58.374710", "-1");

        assertThatThrownBy(() -> mapper.toStation(row))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
