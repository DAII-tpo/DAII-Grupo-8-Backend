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

    @Test
    void mapsBlankOptionalAddressAsNull() {
        EcobiciStationRow row = new EcobiciStationRow(
                "2", "RETIRO I", " ", "-34.592424", "-58.374710", "0");

        assertThat(mapper.toStation(row).getAddress()).isNull();
    }

    @Test
    void rejectsMalformedNumericValues() {
        EcobiciStationRow invalidLatitude = new EcobiciStationRow(
                "2", "RETIRO I", null, "not-a-number", "-58.374710", "20");
        EcobiciStationRow invalidCapacity = new EcobiciStationRow(
                "2", "RETIRO I", null, "-34.592424", "-58.374710", "many");

        assertThatThrownBy(() -> mapper.toStation(invalidLatitude))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decimal number");
        assertThatThrownBy(() -> mapper.toStation(invalidCapacity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("integer");
    }

    @Test
    void acceptsExactCabaBoundaryCoordinates() {
        EcobiciStationRow rowMin = new EcobiciStationRow(
                "1", "MIN", null, "-35", "-59", "10");
        Station sMin = mapper.toStation(rowMin);
        assertThat(sMin.getLatitude()).isEqualByComparingTo(new BigDecimal("-35"));
        assertThat(sMin.getLongitude()).isEqualByComparingTo(new BigDecimal("-59"));

        EcobiciStationRow rowMax = new EcobiciStationRow(
                "2", "MAX", null, "-34", "-58", "10");
        Station sMax = mapper.toStation(rowMax);
        assertThat(sMax.getLatitude()).isEqualByComparingTo(new BigDecimal("-34"));
        assertThat(sMax.getLongitude()).isEqualByComparingTo(new BigDecimal("-58"));
    }

    @Test
    void acceptsStringsAtExactMaxLengthAndRejectsWhenExceeded() {
        String maxName = "A".repeat(150);
        String maxExternalId = "E".repeat(64);
        String maxAddress = "D".repeat(255);
        EcobiciStationRow row = new EcobiciStationRow(
                maxExternalId, maxName, maxAddress, "-34.592424", "-58.374710", "20");
        Station station = mapper.toStation(row);
        assertThat(station.getName()).isEqualTo(maxName);
        assertThat(station.getExternalId()).isEqualTo(maxExternalId);
        assertThat(station.getAddress()).isEqualTo(maxAddress);

        EcobiciStationRow tooLongName = new EcobiciStationRow(
                maxExternalId, maxName + "X", maxAddress, "-34.592424", "-58.374710", "20");
        assertThatThrownBy(() -> mapper.toStation(tooLongName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 150");

        EcobiciStationRow tooLongAddress = new EcobiciStationRow(
                maxExternalId, maxName, maxAddress + "X", "-34.592424", "-58.374710", "20");
        assertThatThrownBy(() -> mapper.toStation(tooLongAddress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 255");
    }
}
