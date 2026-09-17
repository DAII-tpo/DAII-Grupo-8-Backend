package com.citypass.movilidad;

import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.BikeStatusHistory;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.model.enums.TripStatus;
import com.citypass.movilidad.repository.BikeRepository;
import com.citypass.movilidad.repository.BikeStatusHistoryRepository;
import com.citypass.movilidad.repository.RoleRepository;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.TripRepository;
import com.citypass.movilidad.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flujo completo del ciclo de vida de un viaje (MOV-026, MOV-027, MOV-028) y su historial
 * (MOV-030) contra MySQL real: verifica el contrato HTTP y que el estado final de Trip, Bike y
 * bike_status_history sea consistente, incluyendo el rollback cuando una devolución es rechazada.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
class TripFlowIntegrationTest {

    private static final String USER_HEADER = "X-User-Id";

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
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    @Test
    void recorreElCicloCompletoIniciarConsultarYFinalizar() throws Exception {
        User user = user("ciclo@example.com");
        Station origin = station("Origen ciclo", 10);
        Station destination = station("Destino ciclo", 10);
        Bike bike = bike("TRIP-FLOW-1", origin, BikeStatus.AVAILABLE);

        long tripId = startTrip(user, bike);

        mockMvc.perform(get("/api/v1/trips/active").header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tripId))
                .andExpect(jsonPath("$.bikeId").value(bike.getId()))
                .andExpect(jsonPath("$.originStationId").value(origin.getId()))
                .andExpect(jsonPath("$.startedAt").isNotEmpty());

        // Un usuario no puede tener dos viajes activos.
        Bike otherBike = bike("TRIP-FLOW-2", origin, BikeStatus.AVAILABLE);
        mockMvc.perform(post("/api/v1/trips").header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bikeId\":" + otherBike.getId() + "}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/trips/" + tripId + "/end").header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationStationId\":" + destination.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.destinationStationId").value(destination.getId()))
                .andExpect(jsonPath("$.endedAt").isNotEmpty())
                .andExpect(jsonPath("$.durationSeconds").isNumber());

        mockMvc.perform(get("/api/v1/trips/active").header(USER_HEADER, user.getId()))
                .andExpect(status().isNoContent());

        Bike finalBike = bikeRepository.findById(bike.getId()).orElseThrow();
        assertThat(finalBike.getStatus()).isEqualTo(BikeStatus.AVAILABLE);
        assertThat(finalBike.getStation().getId()).isEqualTo(destination.getId());

        Trip finalTrip = tripRepository.findById(tripId).orElseThrow();
        assertThat(finalTrip.getStatus()).isEqualTo(TripStatus.COMPLETED);
        assertThat(finalTrip.getEndedAt()).isNotNull();
        assertThat(finalTrip.getDurationSeconds()).isNotNull();

        assertThat(historyRepository.findAllByBikeIdOrderByChangedAtDesc(bike.getId()))
                .extracting(BikeStatusHistory::getNewStatus)
                .containsExactlyInAnyOrder(BikeStatus.IN_USE, BikeStatus.AVAILABLE);

        // Otra bicicleta del mismo usuario quedó intacta tras el rechazo.
        assertThat(bikeRepository.findById(otherBike.getId()).orElseThrow().getStatus())
                .isEqualTo(BikeStatus.AVAILABLE);
    }

    @Test
    void rechazaDevolverEnEstacionLlenaSinDejarCambiosParciales() throws Exception {
        User user = user("llena@example.com");
        Station origin = station("Origen llena", 10);
        Station full = station("Estación llena", 1);
        bike("TRIP-FULL-OCCUPANT", full, BikeStatus.AVAILABLE);
        Bike bike = bike("TRIP-FULL-1", origin, BikeStatus.AVAILABLE);

        long tripId = startTrip(user, bike);

        mockMvc.perform(post("/api/v1/trips/" + tripId + "/end").header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationStationId\":" + full.getId() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("La estación no tiene capacidad disponible: " + full.getId()));

        Trip trip = tripRepository.findById(tripId).orElseThrow();
        assertThat(trip.getStatus()).isEqualTo(TripStatus.ACTIVE);
        assertThat(trip.getDestinationStation()).isNull();
        assertThat(trip.getEndedAt()).isNull();

        Bike inUse = bikeRepository.findById(bike.getId()).orElseThrow();
        assertThat(inUse.getStatus()).isEqualTo(BikeStatus.IN_USE);
        assertThat(inUse.getStation()).isNull();
        assertThat(historyRepository.findAllByBikeIdOrderByChangedAtDesc(bike.getId()))
                .extracting(BikeStatusHistory::getNewStatus)
                .containsExactly(BikeStatus.IN_USE);

        mockMvc.perform(get("/api/v1/trips/active").header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tripId));
    }

    @Test
    void rechazaIniciarConBicicletaNoDisponibleOSinHeaderDeUsuario() throws Exception {
        User user = user("rechazo@example.com");
        Station origin = station("Origen rechazo", 10);
        Bike bike = bike("TRIP-REJECT-1", origin, BikeStatus.MAINTENANCE);

        mockMvc.perform(post("/api/v1/trips").header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bikeId\":" + bike.getId() + "}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/trips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bikeId\":" + bike.getId() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Falta el header obligatorio " + USER_HEADER));

        assertThat(tripRepository.findByUserIdAndStatus(user.getId(), TripStatus.ACTIVE)).isEmpty();
        Bike unchanged = bikeRepository.findById(bike.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(BikeStatus.MAINTENANCE);
        assertThat(unchanged.getStation().getId()).isEqualTo(origin.getId());
        assertThat(historyRepository.findAllByBikeIdOrderByChangedAtDesc(bike.getId())).isEmpty();
    }

    @Test
    void devuelveElHistorialDeViajesFinalizadosDelMasRecienteAlMasAntiguo() throws Exception {
        User user = user("historial@example.com");
        Station origin = station("Origen historial", 10);
        Station destination = station("Destino historial", 10);

        long primerViaje = completeTrip(user, bike("HIST-1", origin, BikeStatus.AVAILABLE), destination);
        long segundoViaje = completeTrip(user, bike("HIST-2", origin, BikeStatus.AVAILABLE), destination);

        mockMvc.perform(get("/api/v1/trips/history").header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(segundoViaje))
                .andExpect(jsonPath("$.content[1].id").value(primerViaje))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.content[0].originStationId").value(origin.getId()))
                .andExpect(jsonPath("$.content[0].destinationStationId").value(destination.getId()))
                .andExpect(jsonPath("$.content[0].startedAt").isNotEmpty())
                .andExpect(jsonPath("$.content[0].endedAt").isNotEmpty())
                .andExpect(jsonPath("$.content[0].durationSeconds").isNumber())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void noMuestraElHistorialDeOtroUsuarioNiLosViajesEnCurso() throws Exception {
        User owner = user("historial-propio@example.com");
        User other = user("historial-ajeno@example.com");
        Station origin = station("Origen aislamiento", 10);
        Station destination = station("Destino aislamiento", 10);

        long viajeAjeno = completeTrip(other, bike("HIST-AJENO", origin, BikeStatus.AVAILABLE), destination);
        long viajePropio = completeTrip(owner, bike("HIST-PROPIO", origin, BikeStatus.AVAILABLE), destination);
        // Queda un viaje en curso del mismo usuario: el historial es sólo de viajes finalizados.
        long viajeEnCurso = startTrip(owner, bike("HIST-EN-CURSO", origin, BikeStatus.AVAILABLE));

        mockMvc.perform(get("/api/v1/trips/history").header(USER_HEADER, owner.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(viajePropio));

        // Ni el viaje del otro usuario ni el que sigue en curso entran en el historial.
        assertThat(viajeAjeno).isNotEqualTo(viajePropio);
        assertThat(viajeEnCurso).isNotEqualTo(viajePropio);
    }

    @Test
    void devuelveUnaPaginaVaciaCuandoElUsuarioNoTieneViajes() throws Exception {
        User user = user("historial-vacio@example.com");

        // Un usuario nuevo no es un caso de error: 200 con la página vacía.
        mockMvc.perform(get("/api/v1/trips/history").header(USER_HEADER, user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void rechazaElHistorialDeUnUsuarioInexistenteYLaPaginacionInvalida() throws Exception {
        User user = user("historial-validaciones@example.com");

        mockMvc.perform(get("/api/v1/trips/history").header(USER_HEADER, 999999L))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/trips/history").header(USER_HEADER, user.getId()).param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/trips/history").header(USER_HEADER, user.getId()).param("size", "51"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/trips/history").header(USER_HEADER, user.getId()).param("page", "-1"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/trips/history"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Falta el header obligatorio " + USER_HEADER));
    }

    private long completeTrip(User user, Bike bike, Station destination) throws Exception {
        long tripId = startTrip(user, bike);
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/end").header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destinationStationId\":" + destination.getId() + "}"))
                .andExpect(status().isOk());
        return tripId;
    }

    private long startTrip(User user, Bike bike) throws Exception {
        String body = mockMvc.perform(post("/api/v1/trips").header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bikeId\":" + bike.getId() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private User user(String email) {
        User user = new User();
        user.setFirstName("Usuario");
        user.setLastName("Viajes");
        user.setEmail(email);
        user.setRole(roleRepository.findByName("USER").orElseThrow());
        return userRepository.save(user);
    }

    private Station station(String name, int capacity) {
        Station station = new Station();
        station.setName(name);
        station.setLatitude(new BigDecimal("-34.6083000"));
        station.setLongitude(new BigDecimal("-58.3712000"));
        station.setCapacity(capacity);
        station.setSource(StationSource.MANUAL);
        return stationRepository.save(station);
    }

    private Bike bike(String code, Station station, BikeStatus status) {
        Bike bike = new Bike();
        bike.setCode(code);
        bike.setStation(station);
        bike.setStatus(status);
        return bikeRepository.save(bike);
    }
}
