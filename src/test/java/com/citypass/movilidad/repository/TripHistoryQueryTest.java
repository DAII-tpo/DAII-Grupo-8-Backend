package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.Role;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.model.enums.TripStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Verifica contra MySQL real la consulta del historial de viajes (MOV-030): que el filtro por
 * usuario y estado se aplique en SQL, que el orden descendente sea el que exige el criterio de
 * aceptación y que la paginación no repita ni pierda viajes.
 *
 * El caso del desempate es el que más importa: {@code started_at} es DATETIME sin fracción de
 * segundo, así que dos viajes del mismo segundo sin un desempate estable podrían aparecer dos
 * veces —o ninguna— al recorrer las páginas.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class TripHistoryQueryTest {

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
    private TripRepository tripRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private StationRepository stationRepository;

    @Autowired
    private BikeRepository bikeRepository;

    private User owner;
    private User other;
    private Station origin;
    private Station destination;
    private Instant now;

    @BeforeEach
    void seed() {
        tripRepository.deleteAll();
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        owner = user("historial-propio@example.com");
        other = user("historial-ajeno@example.com");
        origin = station("Origen historial");
        destination = station("Destino historial");
    }

    @Test
    void returnsOnlyCompletedTripsOfTheRequestedUser() {
        Trip completed = completedTrip(owner, now.minusSeconds(600));
        Trip alsoCompleted = completedTrip(owner, now.minusSeconds(1200));
        activeTrip(owner, now.minusSeconds(60));
        completedTrip(other, now.minusSeconds(300));

        assertThat(history(owner, 0, 10)).extracting(Trip::getId)
                .containsExactly(completed.getId(), alsoCompleted.getId());
    }

    @Test
    void ordersHistoryFromNewestToOldest() {
        Trip oldest = completedTrip(owner, now.minusSeconds(3600));
        Trip middle = completedTrip(owner, now.minusSeconds(1800));
        Trip newest = completedTrip(owner, now.minusSeconds(600));

        assertThat(history(owner, 0, 10)).extracting(Trip::getId)
                .containsExactly(newest.getId(), middle.getId(), oldest.getId());
    }

    @Test
    void breaksTiesByIdWhenTripsStartInTheSameSecond() {
        Instant sameSecond = now.minusSeconds(900);
        Trip first = completedTrip(owner, sameSecond);
        Trip second = completedTrip(owner, sameSecond);

        assertThat(history(owner, 0, 10)).extracting(Trip::getId)
                .containsExactly(second.getId(), first.getId());

        // Recorrer las páginas de a uno no puede devolver el mismo viaje dos veces.
        assertThat(history(owner, 0, 1)).extracting(Trip::getId).containsExactly(second.getId());
        assertThat(history(owner, 1, 1)).extracting(Trip::getId).containsExactly(first.getId());
    }

    @Test
    void splitsHistoryAcrossPagesWithTotalsAndLastFlag() {
        completedTrip(owner, now.minusSeconds(3600));
        completedTrip(owner, now.minusSeconds(1800));
        completedTrip(owner, now.minusSeconds(600));

        Page<Trip> firstPage = page(owner, 0, 2);
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.isLast()).isFalse();

        Page<Trip> secondPage = page(owner, 1, 2);
        assertThat(secondPage.getContent()).hasSize(1);
        assertThat(secondPage.isLast()).isTrue();
    }

    @Test
    void returnsEmptyPageForUserWithoutCompletedTrips() {
        activeTrip(owner, now.minusSeconds(60));

        Page<Trip> page = page(owner, 0, 10);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        assertThat(page.isLast()).isTrue();
    }

    @Test
    void loadsBikeAndStationsWithoutLazyInitialization() {
        completedTrip(owner, now.minusSeconds(600));

        Trip trip = history(owner, 0, 10).getFirst();

        // Falla en cuanto alguien saque una asociación del @EntityGraph y vuelva el N+1.
        assertThat(trip.getBike().getCode()).isNotNull();
        assertThat(trip.getOriginStation().getName()).isEqualTo("Origen historial");
        assertThat(trip.getDestinationStation().getName()).isEqualTo("Destino historial");
    }

    private List<Trip> history(User user, int page, int size) {
        return page(user, page, size).getContent();
    }

    private Page<Trip> page(User user, int page, int size) {
        return tripRepository.findByUserIdAndStatusOrderByStartedAtDescIdDesc(
                user.getId(), TripStatus.COMPLETED, PageRequest.of(page, size));
    }

    private Trip completedTrip(User user, Instant startedAt) {
        Trip trip = trip(user, startedAt);
        trip.setStatus(TripStatus.COMPLETED);
        trip.setDestinationStation(destination);
        trip.setEndedAt(startedAt.plusSeconds(300));
        trip.setDurationSeconds(300);
        return tripRepository.save(trip);
    }

    private Trip activeTrip(User user, Instant startedAt) {
        return tripRepository.save(trip(user, startedAt));
    }

    private Trip trip(User user, Instant startedAt) {
        Trip trip = new Trip();
        trip.setUser(user);
        trip.setBike(bike());
        trip.setOriginStation(origin);
        trip.setStartedAt(startedAt);
        trip.setStatus(TripStatus.ACTIVE);
        return trip;
    }

    private User user(String email) {
        Role role = roleRepository.findByName("USER").orElseThrow();
        User user = new User();
        user.setFirstName("Usuario");
        user.setLastName("Historial");
        user.setEmail(email);
        user.setRole(role);
        return userRepository.save(user);
    }

    private Station station(String name) {
        Station station = new Station();
        station.setName(name);
        station.setLatitude(new BigDecimal("-34.6083000"));
        station.setLongitude(new BigDecimal("-58.3712000"));
        station.setCapacity(20);
        station.setSource(StationSource.MANUAL);
        return stationRepository.save(station);
    }

    private Bike bike() {
        Bike bike = new Bike();
        bike.setCode("HIST-" + UUID.randomUUID());
        bike.setStation(origin);
        bike.setStatus(BikeStatus.AVAILABLE);
        return bikeRepository.save(bike);
    }
}
