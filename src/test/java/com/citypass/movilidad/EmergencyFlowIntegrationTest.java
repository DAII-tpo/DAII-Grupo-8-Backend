package com.citypass.movilidad;

import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.BikeStatusHistory;
import com.citypass.movilidad.model.EventOutbox;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.model.enums.EventOutboxStatus;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.model.enums.TripStatus;
import com.citypass.movilidad.repository.BikeRepository;
import com.citypass.movilidad.repository.BikeStatusHistoryRepository;
import com.citypass.movilidad.repository.EventOutboxRepository;
import com.citypass.movilidad.repository.RoleRepository;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.TripRepository;
import com.citypass.movilidad.repository.UserRepository;
import com.citypass.movilidad.service.EmergencyDeadlineJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pedido de ayuda durante un viaje contra MySQL real: estados del viaje y de la bici, ventana de
 * cancelación, cuestionario, vencimiento del plazo y el evento EmergenciaBicicleta en el outbox.
 * El reloj es controlable para recorrer los plazos sin esperar.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
class EmergencyFlowIntegrationTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String EVENT_TYPE = "com.citypass.movilidad.EmergenciaBicicleta";
    private static final String LOCATION =
            "{\"latitude\": -34.6037, \"longitude\": -58.3816, \"accuracyMeters\": 12.5}";
    private static final String NO_PUEDE_MOVERSE =
            "{\"canMove\": false, \"bleeding\": false, \"vehicleInvolved\": false}";
    private static final String SIN_LESIONES =
            "{\"canMove\": true, \"bleeding\": false, \"vehicleInvolved\": false}";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("movilidad")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configuracionDinamica(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        // El job se ejecuta a mano desde los tests.
        registry.add("movilidad.emergency.deadline-check-interval", () -> "1h");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private EmergencyDeadlineJob deadlineJob;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StationRepository stationRepository;

    @Autowired
    private BikeRepository bikeRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private BikeStatusHistoryRepository historyRepository;

    @Autowired
    private EventOutboxRepository outboxRepository;

    @BeforeEach
    void setUp() {
        clock.set(Instant.now());
    }

    @Test
    void pedirAyudaPausaElViajeBloqueaLaBiciYConElCuestionarioAvisaAEmergencias() throws Exception {
        Fixture fixture = fixture();

        JsonNode emergency = report(fixture);
        long emergencyId = emergency.get("id").asLong();
        assertThat(emergency.get("status").asText()).isEqualTo("PENDING");
        assertThat(emergency.get("code").asText()).matches("EMG-\\d{8}-\\d{4,}");
        assertThat(emergency.get("cancelWindowSeconds").asLong()).isEqualTo(10);
        assertThat(emergency.get("triageWindowSeconds").asLong()).isEqualTo(60);

        assertThat(tripRepository.findById(fixture.tripId).orElseThrow().getStatus())
                .isEqualTo(TripStatus.EMERGENCY);
        assertThat(bikeRepository.findById(fixture.bike.getId()).orElseThrow().getStatus())
                .isEqualTo(BikeStatus.BLOCKED);
        // Mientras se puede cancelar, Emergencias no se entera.
        assertThat(eventsOf(fixture.tripId)).isEmpty();

        // El viaje pausado se sigue viendo como el viaje en curso, y no se puede finalizar ni iniciar otro.
        mockMvc.perform(get("/api/v1/trips/active").header(USER_HEADER, fixture.user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMERGENCY"));
        mockMvc.perform(endTrip(fixture))
                .andExpect(status().isConflict());
        Bike other = bike(fixture.station);
        mockMvc.perform(post("/api/v1/trips").header(USER_HEADER, fixture.user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bikeId\":" + other.getId() + "}"))
                .andExpect(status().isConflict());

        // El cuestionario se responde recién cuando ya no se puede cancelar.
        mockMvc.perform(triage(fixture, emergencyId, NO_PUEDE_MOVERSE))
                .andExpect(status().isConflict());

        clock.advance(Duration.ofSeconds(11));
        mockMvc.perform(cancel(fixture.user, emergencyId))
                .andExpect(status().isConflict());
        mockMvc.perform(triage(fixture, emergencyId, NO_PUEDE_MOVERSE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPORTED"))
                .andExpect(jsonPath("$.priority").value("P2"))
                .andExpect(jsonPath("$.sentAt").isNotEmpty());

        List<EventOutbox> events = eventsOf(fixture.tripId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getStatus()).isEqualTo(EventOutboxStatus.PENDING);
        JsonNode data = objectMapper.readTree(events.get(0).getPayload());
        assertThat(data.get("emergenciaId").asText()).isEqualTo(emergency.get("code").asText());
        assertThat(data.at("/ubicacion/latitud").asDouble()).isEqualTo(-34.6037);
        assertThat(data.at("/ubicacion/longitud").asDouble()).isEqualTo(-58.3816);
        assertThat(data.at("/ubicacion/precisionMetros").asDouble()).isEqualTo(12.5);
        assertThat(data.at("/triage/respondido").asBoolean()).isTrue();
        assertThat(data.at("/triage/puedeMoverse").asBoolean()).isFalse();
        assertThat(data.at("/triage/prioridadSugerida").asText()).isEqualTo("P2");
        assertThat(data.at("/viaje/viajeId").asLong()).isEqualTo(fixture.tripId);
        assertThat(data.at("/viaje/bicicletaCodigo").asText()).isEqualTo(fixture.bike.getCode());
        assertThat(data.at("/viaje/estacionOrigenId").asLong()).isEqualTo(fixture.station.getId());
        assertThat(data.at("/usuario/usuarioId").asLong()).isEqualTo(fixture.user.getId());
        assertThat(data.at("/usuario/email").asText()).isEqualTo(fixture.user.getEmail());

        // Ya enviada, no se vuelve a enviar.
        mockMvc.perform(triage(fixture, emergencyId, SIN_LESIONES))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/emergencies/current").header(USER_HEADER, fixture.user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(emergencyId))
                .andExpect(jsonPath("$.status").value("REPORTED"));
    }

    @Test
    void cancelarATiempoDejaElViajeComoEstabaYNoAvisaANadie() throws Exception {
        Fixture fixture = fixture();
        long emergencyId = report(fixture).get("id").asLong();

        clock.advance(Duration.ofSeconds(5));
        mockMvc.perform(cancel(fixture.user, emergencyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(tripRepository.findById(fixture.tripId).orElseThrow().getStatus()).isEqualTo(TripStatus.ACTIVE);
        assertThat(bikeRepository.findById(fixture.bike.getId()).orElseThrow().getStatus())
                .isEqualTo(BikeStatus.IN_USE);
        assertThat(historyRepository.findAllByBikeIdOrderByChangedAtDesc(fixture.bike.getId()))
                .extracting(BikeStatusHistory::getNewStatus)
                .containsExactlyInAnyOrder(BikeStatus.IN_USE, BikeStatus.BLOCKED, BikeStatus.IN_USE);

        clock.advance(Duration.ofMinutes(5));
        deadlineJob.sendExpired();
        assertThat(eventsOf(fixture.tripId)).isEmpty();
        mockMvc.perform(get("/api/v1/emergencies/current").header(USER_HEADER, fixture.user.getId()))
                .andExpect(status().isNoContent());

        // El viaje se puede terminar normalmente.
        mockMvc.perform(endTrip(fixture))
                .andExpect(status().isOk());
    }

    @Test
    void sinCuestionarioAlVencerElPlazoSeAvisaIgualConLaPeorPrioridad() throws Exception {
        Fixture fixture = fixture();
        long emergencyId = report(fixture).get("id").asLong();

        clock.advance(Duration.ofSeconds(30));
        deadlineJob.sendExpired();
        assertThat(eventsOf(fixture.tripId)).isEmpty();

        clock.advance(Duration.ofSeconds(41));
        deadlineJob.sendExpired();

        List<EventOutbox> events = eventsOf(fixture.tripId);
        assertThat(events).hasSize(1);
        JsonNode data = objectMapper.readTree(events.get(0).getPayload());
        assertThat(data.at("/triage/respondido").asBoolean()).isFalse();
        assertThat(data.at("/triage/puedeMoverse").isNull()).isTrue();
        assertThat(data.at("/triage/prioridadSugerida").asText()).isEqualTo("P1");

        // El cuestionario tardío ya no cambia nada, y el job no lo vuelve a enviar.
        mockMvc.perform(triage(fixture, emergencyId, SIN_LESIONES))
                .andExpect(status().isConflict());
        deadlineJob.sendExpired();
        assertThat(eventsOf(fixture.tripId)).hasSize(1);
    }

    @Test
    void rechazaPedidosInvalidosOAjenos() throws Exception {
        Fixture fixture = fixture();
        User stranger = user();

        mockMvc.perform(reportRequest(fixture.tripId, fixture.user, "{\"latitude\": -134.6, \"longitude\": -58.38}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(reportRequest(fixture.tripId, stranger, LOCATION))
                .andExpect(status().isNotFound());

        long emergencyId = report(fixture).get("id").asLong();
        mockMvc.perform(reportRequest(fixture.tripId, fixture.user, LOCATION))
                .andExpect(status().isConflict());
        mockMvc.perform(cancel(stranger, emergencyId))
                .andExpect(status().isNotFound());

        clock.advance(Duration.ofSeconds(11));
        mockMvc.perform(triage(fixture, emergencyId, "{\"canMove\": true}"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode report(Fixture fixture) throws Exception {
        String body = mockMvc.perform(reportRequest(fixture.tripId, fixture.user, LOCATION))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private RequestBuilder reportRequest(long tripId, User user, String location) {
        return post("/api/v1/trips/" + tripId + "/emergencies").header(USER_HEADER, user.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(location);
    }

    private RequestBuilder cancel(User user, long emergencyId) {
        return post("/api/v1/emergencies/" + emergencyId + "/cancel").header(USER_HEADER, user.getId());
    }

    private RequestBuilder endTrip(Fixture fixture) {
        return post("/api/v1/trips/" + fixture.tripId + "/end").header(USER_HEADER, fixture.user.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"destinationStationId\":" + fixture.station.getId() + "}");
    }

    private RequestBuilder triage(Fixture fixture, long emergencyId, String answers) {
        return post("/api/v1/emergencies/" + emergencyId + "/triage").header(USER_HEADER, fixture.user.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(answers);
    }

    private List<EventOutbox> eventsOf(long tripId) {
        return outboxRepository.findAll().stream()
                .filter(event -> EVENT_TYPE.equals(event.getEventType()))
                .filter(event -> String.valueOf(tripId).equals(event.getAggregateId()))
                .toList();
    }

    private Fixture fixture() throws Exception {
        User user = user();
        Station station = station();
        Bike bike = bike(station);
        String body = mockMvc.perform(post("/api/v1/trips").header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bikeId\":" + bike.getId() + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Fixture(user, station, bike, objectMapper.readTree(body).get("id").asLong());
    }

    private User user() {
        User user = new User();
        user.setFirstName("Ana");
        user.setLastName("Pérez");
        user.setEmail("emergencia-" + UUID.randomUUID() + "@example.com");
        user.setRole(roleRepository.findByName("USER").orElseThrow());
        return userRepository.save(user);
    }

    private Station station() {
        Station station = new Station();
        station.setName("Estación " + UUID.randomUUID());
        station.setLatitude(new BigDecimal("-34.6083000"));
        station.setLongitude(new BigDecimal("-58.3712000"));
        station.setCapacity(10);
        station.setSource(StationSource.MANUAL);
        return stationRepository.save(station);
    }

    private Bike bike(Station station) {
        Bike bike = new Bike();
        bike.setCode("EMG-BIKE-" + UUID.randomUUID().toString().substring(0, 8));
        bike.setStation(station);
        bike.setStatus(BikeStatus.AVAILABLE);
        return bikeRepository.save(bike);
    }

    private record Fixture(User user, Station station, Bike bike, long tripId) {
    }

    @TestConfiguration
    static class RelojDePrueba {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.now());
        }
    }

    static class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
