package com.citypass.movilidad.service;

import com.citypass.movilidad.importer.StationImportResult;
import com.citypass.movilidad.mapper.EcobiciStationMapper;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.repository.StationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcobiciStationImportServiceTest {

    private StationRepository repository;
    private EcobiciStationImportService service;

    @BeforeEach
    void setUp() {
        repository = mock(StationRepository.class);
        service = new EcobiciStationImportService(repository, new EcobiciStationMapper(), new ObjectMapper());
        when(repository.saveAndFlush(any(Station.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void importsValidRowsAndContinuesAfterInvalidRows() throws Exception {
        String geoJson = featureCollection(
                feature(2, 2, "RETIRO I", "Av. Ramos Mejia 1300", -34.592424, -58.374710, 20),
                feature(3, 3, "INVALIDA", "Fuera de CABA", -23.569064, -46.697839, 20),
                feature(4, 4, "CATEDRAL", "Bartolome Mitre 470", -34.606745, -58.373656, 24));

        StationImportResult result = service.importStations(resource(geoJson));

        assertThat(result).isEqualTo(new StationImportResult(3, 2, 0, 1));
        verify(repository, org.mockito.Mockito.times(2)).saveAndFlush(any(Station.class));
    }

    @Test
    void skipsExistingStationsWithoutOverwritingManualChanges() throws Exception {
        String geoJson = featureCollection(
                feature(2, 2, "RETIRO I", "Av. Ramos Mejia 1300", -34.592424, -58.374710, 20));
        when(repository.findByExternalId("ecobici-2-2")).thenReturn(Optional.of(new Station()));

        StationImportResult result = service.importStations(resource(geoJson));

        assertThat(result).isEqualTo(new StationImportResult(1, 0, 1, 0));
        verify(repository, never()).saveAndFlush(any(Station.class));
    }

    @Test
    void importsCapacityFromOfficialAnchorsProperty() throws Exception {
        String geoJson = featureCollection(
                feature(202, 1, "FACULTAD DE DERECHO", "Figueroa Alcorta 2120", -34.5842, -58.3905, 30));

        StationImportResult result = service.importStations(resource(geoJson));

        assertThat(result.imported()).isEqualTo(1);
        verify(repository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(station -> station.getCapacity() == 30));
    }

    @Test
    void importsBundledOfficialDatasetAndSkipsRepeatedCompositeIds() throws Exception {
        StationImportResult result = service.importStations(
                new ClassPathResource("datasets/ecobici-stations.geojson"));

        assertThat(result).isEqualTo(new StationImportResult(471, 468, 3, 0));
        verify(repository, org.mockito.Mockito.times(468)).saveAndFlush(any(Station.class));
    }

    private String featureCollection(String... features) {
        return "{\"type\":\"FeatureCollection\",\"features\":[" + String.join(",", features) + "]}";
    }

    private String feature(int id, int number, String name, String address, double lat, double lon, int anchors) {
        return "{\"type\":\"Feature\",\"properties\":{" 
                + "\"ID\":" + id + ",\"NUMERO\":" + number
                + ",\"NOMBRE\":\"" + name + "\",\"DIRECCION\":\"" + address + "\""
                + ",\"Lat\":" + lat + ",\"Lon\":" + lon + ",\"ANCLAJES\":" + anchors + "}}";
    }

    private ByteArrayResource resource(String geoJson) {
        return new ByteArrayResource(geoJson.getBytes(StandardCharsets.UTF_8));
    }
}
