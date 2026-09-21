package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.projection.StationBikeCountProjection;
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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Verifica contra MySQL real que la consulta agrupada de disponibilidad (MOV-016) se traduce
 * bien a SQL: una JPQL con projection y agregados solo falla en tiempo de ejecución.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class StationAvailabilityQueryTest {

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

    @Autowired
    private BikeRepository bikeRepository;

    @Test
    void cuentaBicicletasPresentesYDisponiblesPorEstacion() {
        Station conBicis = stationRepository.save(station("Estación con bicis", StationStatus.ACTIVE));
        Station vacia = stationRepository.save(station("Estación vacía", StationStatus.ACTIVE));

        bikeRepository.save(bike("Q-1", conBicis, BikeStatus.AVAILABLE, null));
        bikeRepository.save(bike("Q-2", conBicis, BikeStatus.AVAILABLE, null));
        bikeRepository.save(bike("Q-3", conBicis, BikeStatus.MAINTENANCE, null));
        // Dada de baja lógica: no debe contarse ni como presente ni como disponible.
        bikeRepository.save(bike("Q-4", conBicis, BikeStatus.AVAILABLE, Instant.now()));
        // En uso: sin estación asignada, no ocupa anclaje en ninguna parte.
        bikeRepository.save(bike("Q-5", null, BikeStatus.IN_USE, null));

        Map<Long, StationBikeCountProjection> counts = bikeRepository
                .countBikesByStationIds(List.of(conBicis.getId(), vacia.getId()), BikeStatus.AVAILABLE).stream()
                .collect(Collectors.toMap(StationBikeCountProjection::getStationId, Function.identity()));

        assertThat(counts).containsOnlyKeys(conBicis.getId());
        assertThat(counts.get(conBicis.getId()).getTotalBikes()).isEqualTo(3L);
        assertThat(counts.get(conBicis.getId()).getAvailableBikes()).isEqualTo(2L);
    }

    @Test
    void listaLasEstacionesVigentesYExcluyeLasDadasDeBaja() {
        Station vigente = stationRepository.save(station("Estación vigente", StationStatus.INACTIVE));
        Station eliminada = station("Estación eliminada", StationStatus.ACTIVE);
        eliminada.setDeletedAt(Instant.now());
        stationRepository.save(eliminada);

        assertThat(stationRepository.findAllByDeletedAtIsNullOrderByName())
                .extracting(Station::getName)
                .contains(vigente.getName())
                .doesNotContain(eliminada.getName());
        assertThat(stationRepository.findByIdAndDeletedAtIsNull(eliminada.getId())).isEmpty();
        assertThat(stationRepository.findByIdAndDeletedAtIsNull(vigente.getId())).isPresent();
    }

    private Station station(String name, StationStatus status) {
        Station station = new Station();
        station.setName(name);
        station.setAddress("Dirección de prueba");
        station.setLatitude(new BigDecimal("-34.6037000"));
        station.setLongitude(new BigDecimal("-58.3816000"));
        station.setCapacity(20);
        station.setStatus(status);
        station.setSource(StationSource.MANUAL);
        return station;
    }

    private Bike bike(String code, Station station, BikeStatus status, Instant deletedAt) {
        Bike bike = new Bike();
        bike.setCode(code);
        bike.setStation(station);
        bike.setStatus(status);
        bike.setDeletedAt(deletedAt);
        return bike;
    }
}
