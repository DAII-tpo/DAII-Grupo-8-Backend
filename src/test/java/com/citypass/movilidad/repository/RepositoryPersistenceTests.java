package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.BikeIncident;
import com.citypass.movilidad.model.BikeStatusHistory;
import com.citypass.movilidad.model.EventOutbox;
import com.citypass.movilidad.model.IncidentType;
import com.citypass.movilidad.model.MaintenanceRecord;
import com.citypass.movilidad.model.Role;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.StationAvailabilityHistory;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.model.enums.BikeIncidentStatus;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.model.enums.EventOutboxStatus;
import com.citypass.movilidad.model.enums.MaintenanceStatus;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.model.enums.TripStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class RepositoryPersistenceTests {

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
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StationRepository stationRepository;

    @Autowired
    private BikeRepository bikeRepository;

    @Autowired
    private IncidentTypeRepository incidentTypeRepository;

    @Autowired
    private BikeStatusHistoryRepository bikeStatusHistoryRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private BikeIncidentRepository bikeIncidentRepository;

    @Autowired
    private MaintenanceRecordRepository maintenanceRecordRepository;

    @Autowired
    private StationAvailabilityHistoryRepository stationAvailabilityHistoryRepository;

    @Autowired
    private EventOutboxRepository eventOutboxRepository;

    @Test
    void persisteYRecuperaUnGrafoDeEntidadesRelacionadas() {
        Role role = roleRepository.findByName("USER").orElseThrow();

        User user = new User();
        user.setFirstName("Ada");
        user.setLastName("Lovelace");
        user.setEmail("ada@example.com");
        user.setRole(role);
        User savedUser = userRepository.save(user);

        Station station = new Station();
        station.setName("Plaza de Mayo");
        station.setLatitude(new BigDecimal("-34.6083000"));
        station.setLongitude(new BigDecimal("-58.3712000"));
        station.setCapacity(20);
        station.setSource(StationSource.MANUAL);
        Station savedStation = stationRepository.save(station);

        Bike bike = new Bike();
        bike.setCode("BIKE-0001");
        bike.setStation(savedStation);
        bikeRepository.save(bike);

        assertThat(savedUser.getId()).isNotNull();
        assertThat(userRepository.findByEmail("ada@example.com")).isPresent();

        assertThat(bikeRepository.findByCode("BIKE-0001"))
                .isPresent()
                .get()
                .satisfies(b -> assertThat(b.getStation().getId()).isEqualTo(savedStation.getId()));
    }

    @Test
    void seedDeReferenciaEstaDisponible() {
        assertThat(roleRepository.findByName("ADMIN")).isPresent();
        assertThat(roleRepository.findByName("OPERATOR")).isPresent();
        assertThat(roleRepository.findByName("USER")).isPresent();
        assertThat(incidentTypeRepository.findByCode("FLAT_TIRE")).isPresent();
    }

    @Test
    void persisteYRecuperaElRestoDelGrafoDeEntidades() {
        Role role = roleRepository.findByName("USER").orElseThrow();

        User user = new User();
        user.setFirstName("Grace");
        user.setLastName("Hopper");
        user.setEmail("grace@example.com");
        user.setRole(role);
        User savedUser = userRepository.save(user);

        Station origin = new Station();
        origin.setName("Retiro");
        origin.setLatitude(new BigDecimal("-34.5924000"));
        origin.setLongitude(new BigDecimal("-58.3731000"));
        origin.setCapacity(15);
        origin.setSource(StationSource.DATASET);
        Station savedOrigin = stationRepository.save(origin);

        Station destination = new Station();
        destination.setName("Recoleta");
        destination.setLatitude(new BigDecimal("-34.5875000"));
        destination.setLongitude(new BigDecimal("-58.3925000"));
        destination.setCapacity(18);
        destination.setSource(StationSource.DATASET);
        Station savedDestination = stationRepository.save(destination);

        Bike bike = new Bike();
        bike.setCode("BIKE-0002");
        bike.setStatus(BikeStatus.AVAILABLE);
        Bike savedBike = bikeRepository.save(bike);

        BikeStatusHistory statusHistory = new BikeStatusHistory();
        statusHistory.setBike(savedBike);
        statusHistory.setPreviousStatus(BikeStatus.MAINTENANCE);
        statusHistory.setNewStatus(BikeStatus.AVAILABLE);
        statusHistory.setChangedByUser(savedUser);
        statusHistory.setReason("Mantenimiento preventivo completado");
        BikeStatusHistory savedStatusHistory = bikeStatusHistoryRepository.save(statusHistory);

        Trip trip = new Trip();
        trip.setUser(savedUser);
        trip.setBike(savedBike);
        trip.setOriginStation(savedOrigin);
        trip.setDestinationStation(savedDestination);
        trip.setStartedAt(Instant.now().minusSeconds(1800));
        trip.setEndedAt(Instant.now());
        trip.setStatus(TripStatus.COMPLETED);
        trip.setDistanceKm(new BigDecimal("3.20"));
        trip.setDurationSeconds(1800);
        Trip savedTrip = tripRepository.save(trip);

        IncidentType incidentType = incidentTypeRepository.findByCode("FLAT_TIRE").orElseThrow();

        BikeIncident incident = new BikeIncident();
        incident.setBike(savedBike);
        incident.setReportedByUser(savedUser);
        incident.setTrip(savedTrip);
        incident.setIncidentType(incidentType);
        incident.setDescription("Pinchazo detectado al finalizar el viaje");
        incident.setStatus(BikeIncidentStatus.RESOLVED);
        incident.setResolvedAt(Instant.now());
        incident.setResolvedByUser(savedUser);
        BikeIncident savedIncident = bikeIncidentRepository.save(incident);

        MaintenanceRecord maintenanceRecord = new MaintenanceRecord();
        maintenanceRecord.setBike(savedBike);
        maintenanceRecord.setIncident(savedIncident);
        maintenanceRecord.setCreatedByUser(savedUser);
        maintenanceRecord.setDescription("Reemplazo de cámara de aire");
        maintenanceRecord.setStatus(MaintenanceStatus.COMPLETED);
        maintenanceRecord.setStartedAt(Instant.now().minusSeconds(600));
        maintenanceRecord.setCompletedAt(Instant.now());
        maintenanceRecord.setResolution("Cámara reemplazada, rueda balanceada");
        MaintenanceRecord savedMaintenanceRecord = maintenanceRecordRepository.save(maintenanceRecord);

        StationAvailabilityHistory availabilityHistory = new StationAvailabilityHistory();
        availabilityHistory.setStation(savedOrigin);
        availabilityHistory.setAvailableBikes(5);
        availabilityHistory.setAvailableSlots(10);
        StationAvailabilityHistory savedAvailabilityHistory =
                stationAvailabilityHistoryRepository.save(availabilityHistory);

        EventOutbox event = new EventOutbox();
        event.setAggregateType(AggregateType.BIKE);
        event.setAggregateId(savedBike.getId().toString());
        event.setEventType("BIKE_STATUS_CHANGED");
        event.setPayload("{\"bikeId\":" + savedBike.getId() + ",\"status\":\"AVAILABLE\"}");
        event.setStatus(EventOutboxStatus.PENDING);
        EventOutbox savedEvent = eventOutboxRepository.save(event);

        assertThat(bikeStatusHistoryRepository.findById(savedStatusHistory.getId()))
                .isPresent()
                .get()
                .satisfies(h -> assertThat(h.getNewStatus()).isEqualTo(BikeStatus.AVAILABLE));

        assertThat(tripRepository.findById(savedTrip.getId()))
                .isPresent()
                .get()
                .satisfies(t -> assertThat(t.getDestinationStation().getId()).isEqualTo(savedDestination.getId()));

        assertThat(bikeIncidentRepository.findById(savedIncident.getId()))
                .isPresent()
                .get()
                .satisfies(i -> assertThat(i.getStatus()).isEqualTo(BikeIncidentStatus.RESOLVED));

        assertThat(maintenanceRecordRepository.findById(savedMaintenanceRecord.getId()))
                .isPresent()
                .get()
                .satisfies(m -> assertThat(m.getIncident().getId()).isEqualTo(savedIncident.getId()));

        assertThat(stationAvailabilityHistoryRepository.findById(savedAvailabilityHistory.getId()))
                .isPresent()
                .get()
                .satisfies(a -> assertThat(a.getAvailableBikes()).isEqualTo(5));

        assertThat(eventOutboxRepository.findById(savedEvent.getId()))
                .isPresent()
                .get()
                .satisfies(e -> assertThat(e.getAggregateType()).isEqualTo(AggregateType.BIKE));
    }
}
