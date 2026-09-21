package com.citypass.movilidad;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
class MovilidadBackendApplicationTests {

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

    @Test
    void elContextoDeSpringLevantaCorrectamente() {
    }

    @Test
    void laDisponibilidadDeEstacionesEsAccesibleSinAutenticacion() throws Exception {
        mockMvc.perform(get("/api/v1/stations/availability")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/stations/1/availability")).andExpect(status().isNotFound());
    }

    @Test
    void openApiDocumentaElContratoRestImplementado() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/api/v1/bikes']['post']['responses']['201']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/bikes/{id}']['get']['responses']['404']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/bikes/{id}']['delete']['responses']['204']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/bikes/station/{stationId}']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/bikes/available']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/bikes/{id}/status']['patch']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/bikes/{id}/station']['patch']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/bikes/{id}/status-history']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/stations']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/stations']['post']['responses']['200']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/stations/{id}']['get']['responses']['404']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/stations/{id}']['patch']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/stations/availability']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/stations/{stationId}/availability']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/stations/nearby']['get']['responses']['400']").exists());
    }
}
