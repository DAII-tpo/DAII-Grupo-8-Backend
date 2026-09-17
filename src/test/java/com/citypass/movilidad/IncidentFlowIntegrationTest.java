package com.citypass.movilidad;

import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.BikeIncident;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.BikeIncidentStatus;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.repository.BikeIncidentRepository;
import com.citypass.movilidad.repository.BikeRepository;
import com.citypass.movilidad.repository.IncidentTypeRepository;
import com.citypass.movilidad.repository.RoleRepository;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
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

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
class IncidentFlowIntegrationTest {

    private static final String USER_HEADER = "X-User-Id";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("movilidad")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
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
    private IncidentTypeRepository typeRepository;
    @Autowired
    private BikeIncidentRepository incidentRepository;

    @Test
    void listsTypesAndPersistsValidIncident() throws Exception {
        User user = user();
        Bike bike = bike();
        Long typeId = typeRepository.findByCode("FLAT_TIRE").orElseThrow().getId();

        mockMvc.perform(get("/api/v1/incidents/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'FLAT_TIRE')]").exists());

        String body = mockMvc.perform(post("/api/v1/incidents").header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bikeId\":" + bike.getId() + ",\"incidentTypeId\":" + typeId
                                + ",\"description\":\"Rueda trasera desinflada\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.bikeId").value(bike.getId()))
                .andExpect(jsonPath("$.reportedByUserId").value(user.getId()))
                .andExpect(jsonPath("$.reportedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        BikeIncident saved = incidentRepository.findById(response.get("id").asLong()).orElseThrow();
        assertThat(saved.getBike().getId()).isEqualTo(bike.getId());
        assertThat(saved.getReportedByUser().getId()).isEqualTo(user.getId());
        assertThat(saved.getIncidentType().getId()).isEqualTo(typeId);
        assertThat(saved.getStatus()).isEqualTo(BikeIncidentStatus.OPEN);
        assertThat(saved.getReportedAt()).isNotNull();
    }

    @Test
    void rejectsInvalidReportWithoutPersistingIt() throws Exception {
        User user = user();
        Long typeId = typeRepository.findByCode("OTHER").orElseThrow().getId();
        long before = incidentRepository.count();

        mockMvc.perform(post("/api/v1/incidents").header(USER_HEADER, user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bikeId\":999999999,\"incidentTypeId\":" + typeId
                                + ",\"description\":\"Bicicleta inexistente\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Bicicleta no encontrada: 999999999"));

        assertThat(incidentRepository.count()).isEqualTo(before);
    }

    private User user() {
        User user = new User();
        user.setFirstName("Usuario");
        user.setLastName("Incidencias");
        user.setEmail("incidencias-" + System.nanoTime() + "@example.com");
        user.setRole(roleRepository.findByName("USER").orElseThrow());
        return userRepository.save(user);
    }

    private Bike bike() {
        Station station = new Station();
        station.setName("Estación incidentes " + System.nanoTime());
        station.setLatitude(new BigDecimal("-34.6083000"));
        station.setLongitude(new BigDecimal("-58.3712000"));
        station.setCapacity(10);
        station.setSource(StationSource.MANUAL);
        station = stationRepository.save(station);

        Bike bike = new Bike();
        bike.setCode("INCIDENT-BIKE-" + System.nanoTime());
        bike.setStation(station);
        bike.setStatus(BikeStatus.AVAILABLE);
        return bikeRepository.save(bike);
    }
}
