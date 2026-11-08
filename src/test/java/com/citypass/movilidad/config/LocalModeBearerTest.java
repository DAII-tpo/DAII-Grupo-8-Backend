package com.citypass.movilidad.config;

import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.repository.BikeRepository;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.TripRepository;
import com.citypass.movilidad.support.TestSecrets;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Modo local con el resource server montado.
 *
 * <p>Este test existe por un riesgo concreto: montar el resource server en la cadena local es lo
 * que hace que el login de ciudadanos funcione con {@code SECURITY_LOCAL_ENABLED=true}, pero si de
 * paso hiciera que un request <em>sin</em> header devolviera 401, se rompería toda la API para el
 * frontend actual. {@code unRequestSinHeaderSiguePasando} es la medición de eso.
 *
 * <p>No configura el secreto a propósito: así ejercita también el camino en que el modo local
 * genera una clave aleatoria al arrancar, que es lo que evita tener un secreto de firma en el repo.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
class LocalModeBearerTest {


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
        // El job de emergencias corre cada 5 s por defecto y, al apagarse el contenedor, deja
        // errores de transacción en el log. No rompe nada, pero es ruido inútil.
        registry.add("movilidad.emergency.deadline-check-interval", () -> "1h");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StationRepository stationRepository;

    @Autowired
    private BikeRepository bikeRepository;

    @Autowired
    private TripRepository tripRepository;

    /** El modo abierto sigue abierto: sin header, los endpoints responden como siempre. */
    @Test
    void unRequestSinHeaderSiguePasando() throws Exception {
        mockMvc.perform(get("/api/v1/stations")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/stations/availability")).andExpect(status().isOk());
        // Acá solo importa que no se rechace por falta de token. Si el usuario demo tiene o no un
        // viaje activo depende de lo que hayan dejado los otros tests en la misma base, así que
        // afirmar 204 haría que este test dependiera del orden de ejecución.
        mockMvc.perform(get("/api/v1/trips/active"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    /** Un header presente pero inválido sí falla, que es lo correcto. */
    @Test
    void unTokenBasuraDa401AunEnModoLocal() throws Exception {
        mockMvc.perform(get("/api/v1/trips/active")
                        .header("Authorization", "Bearer no.es.un.token"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Lo que este modo rompía antes: el token se ignoraba en silencio, no quedaba nada en el
     * SecurityContext, y el viaje se imputaba al usuario demo de la V3.
     */
    @Test
    void conElTokenDeUnCiudadanoElViajeQuedaImputadoAEl() throws Exception {
        String email = "local-" + UUID.randomUUID() + "@example.com";
        String registro = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TestSecrets.CITIZEN_PASSWORD
                                + "\",\"firstName\":\"Ana\",\"lastName\":\"Pérez\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        var cuenta = objectMapper.readTree(registro);
        long ciudadanoId = cuenta.get("userId").asLong();
        String token = cuenta.get("accessToken").asText();

        Station estacion = estacion();
        Bike bici = bici(estacion);

        String viaje = mockMvc.perform(post("/api/v1/trips")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bikeId\":" + bici.getId() + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Trip guardado = tripRepository.findById(objectMapper.readTree(viaje).get("id").asLong())
                .orElseThrow();

        assertThat(guardado.getUser().getId()).isEqualTo(ciudadanoId);
        assertThat(ciudadanoId).isNotEqualTo(1L);
    }

    /** Sin token, la identidad sigue cayendo al usuario demo: es el comportamiento del modo local. */
    @Test
    void sinTokenLaIdentidadCaeAlUsuarioDemo() throws Exception {
        Station estacion = estacion();
        Bike bici = bici(estacion);

        String viaje = mockMvc.perform(post("/api/v1/trips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bikeId\":" + bici.getId() + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Trip guardado = tripRepository.findById(objectMapper.readTree(viaje).get("id").asLong())
                .orElseThrow();

        assertThat(guardado.getUser().getId()).isEqualTo(1L);
    }

    private Station estacion() {
        Station station = new Station();
        station.setName("Estación local " + UUID.randomUUID());
        station.setLatitude(new BigDecimal("-34.6083000"));
        station.setLongitude(new BigDecimal("-58.3712000"));
        station.setCapacity(10);
        station.setSource(StationSource.MANUAL);
        return stationRepository.save(station);
    }

    private Bike bici(Station estacion) {
        Bike bike = new Bike();
        bike.setCode("LOC-" + UUID.randomUUID().toString().substring(0, 8));
        bike.setStation(estacion);
        bike.setStatus(BikeStatus.AVAILABLE);
        return bikeRepository.save(bike);
    }
}
