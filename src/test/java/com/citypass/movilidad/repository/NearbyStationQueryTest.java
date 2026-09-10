package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.projection.NearbyStationProjection;
import com.citypass.movilidad.service.GeoBoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Verifica contra MySQL real la consulta geoespacial de MOV-017. Es el único test que prueba
 * de verdad que ST_Distance_Sphere se traduzca bien a SQL, que las distancias sean correctas
 * y que el prefiltro por caja no deje afuera estaciones que sí están dentro del radio.
 *
 * Las coordenadas son reales de Buenos Aires: si alguien invirtiera los argumentos de POINT
 * (que espera longitud, latitud), las distancias se irían por miles de kilómetros y estas
 * aserciones fallarían.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class NearbyStationQueryTest {

    private static final double OBELISCO_LAT = -34.6037;
    private static final double OBELISCO_LNG = -58.3816;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("movilidad")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private StationRepository stationRepository;

    @BeforeEach
    void seedStations() {
        stationRepository.deleteAll();
        stationRepository.save(station("Obelisco", "-34.6037000", "-58.3816000", StationStatus.ACTIVE, null));
        stationRepository.save(station("Plaza de Mayo", "-34.6083000", "-58.3712000", StationStatus.ACTIVE, null));
        stationRepository.save(station("Congreso", "-34.6097000", "-58.3925000", StationStatus.ACTIVE, null));
        stationRepository.save(station("La Boca", "-34.6345000", "-58.3632000", StationStatus.ACTIVE, null));
    }

    @Test
    void devuelveLasEstacionesOrdenadasPorDistanciaConValoresRealistas() {
        List<NearbyStationProjection> result = findNearby(5000, 10);

        assertThat(result).extracting(NearbyStationProjection::getName)
                .containsExactly("Obelisco", "Plaza de Mayo", "Congreso", "La Boca");

        // Distancias reales aproximadas desde el Obelisco.
        assertThat(result.get(0).getDistanceMeters()).isCloseTo(0, within(20.0));
        assertThat(result.get(1).getDistanceMeters()).isCloseTo(1080, within(200.0));
        assertThat(result.get(2).getDistanceMeters()).isCloseTo(1200, within(200.0));
        assertThat(result.get(3).getDistanceMeters()).isCloseTo(3820, within(400.0));
    }

    @Test
    void elRadioDejaAfueraLasEstacionesMasLejanas() {
        assertThat(findNearby(1500, 10)).extracting(NearbyStationProjection::getName)
                .containsExactly("Obelisco", "Plaza de Mayo", "Congreso");

        assertThat(findNearby(300, 10)).extracting(NearbyStationProjection::getName)
                .containsExactly("Obelisco");
    }

    @Test
    void respetaElLimiteDeResultados() {
        assertThat(findNearby(5000, 2)).extracting(NearbyStationProjection::getName)
                .containsExactly("Obelisco", "Plaza de Mayo");
    }

    @Test
    void excluyeLasEstacionesInactivasYLasDadasDeBaja() {
        stationRepository.save(station("Inactiva", "-34.6040000", "-58.3820000", StationStatus.INACTIVE, null));
        stationRepository.save(station("En mantenimiento", "-34.6041000", "-58.3821000",
                StationStatus.MAINTENANCE, null));
        stationRepository.save(station("Eliminada", "-34.6042000", "-58.3822000",
                StationStatus.ACTIVE, Instant.now()));

        assertThat(findNearby(5000, 20)).extracting(NearbyStationProjection::getName)
                .doesNotContain("Inactiva", "En mantenimiento", "Eliminada")
                .contains("Obelisco");
    }

    @Test
    void devuelveTodosLosCamposQueNecesitaLaRespuesta() {
        NearbyStationProjection obelisco = findNearby(300, 10).getFirst();

        assertThat(obelisco.getId()).isNotNull();
        assertThat(obelisco.getName()).isEqualTo("Obelisco");
        assertThat(obelisco.getAddress()).isEqualTo("Dirección de Obelisco");
        assertThat(obelisco.getLatitude()).isEqualByComparingTo("-34.6037000");
        assertThat(obelisco.getLongitude()).isEqualByComparingTo("-58.3816000");
        assertThat(obelisco.getCapacity()).isEqualTo(20);
    }

    @Test
    void noDevuelveNadaCuandoNoHayEstacionesEnElRadio() {
        // Coordenadas de Madrid: ninguna estación de Buenos Aires cae cerca.
        GeoBoundingBox box = GeoBoundingBox.around(40.4168, -3.7038, 1000);
        assertThat(stationRepository.findNearby(40.4168, -3.7038,
                box.minLatitude(), box.maxLatitude(), box.minLongitude(), box.maxLongitude(),
                1000, StationStatus.ACTIVE.name(), 10)).isEmpty();
    }

    private List<NearbyStationProjection> findNearby(int radiusMeters, int maxResults) {
        GeoBoundingBox box = GeoBoundingBox.around(OBELISCO_LAT, OBELISCO_LNG, radiusMeters);
        return stationRepository.findNearby(OBELISCO_LAT, OBELISCO_LNG,
                box.minLatitude(), box.maxLatitude(), box.minLongitude(), box.maxLongitude(),
                radiusMeters, StationStatus.ACTIVE.name(), maxResults);
    }

    private Station station(String name, String latitude, String longitude,
                            StationStatus status, Instant deletedAt) {
        Station station = new Station();
        station.setName(name);
        station.setAddress("Dirección de " + name);
        station.setLatitude(new BigDecimal(latitude));
        station.setLongitude(new BigDecimal(longitude));
        station.setCapacity(20);
        station.setStatus(status);
        station.setSource(StationSource.MANUAL);
        station.setDeletedAt(deletedAt);
        return station;
    }
}
