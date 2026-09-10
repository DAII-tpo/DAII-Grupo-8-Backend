package com.citypass.movilidad.config;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica la cadena de seguridad "de producción" (security.local.enabled=false), que es la
 * única donde las reglas por endpoint tienen efecto: en modo local todo es permitAll.
 * Sin este test, la exposición pública de la disponibilidad (MOV-016) no está cubierta por
 * ninguna prueba y solo se descubriría un error cuando exista el authorization server.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = "security.local.enabled=false")
class SecurityConfigSecureModeTest {

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
    private MockMvc mockMvc;

    @Test
    void laDisponibilidadSigueSiendoPublicaEnModoSeguro() throws Exception {
        mockMvc.perform(get("/api/v1/stations/availability")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/stations/1/availability")).andExpect(status().isNotFound());
    }

    @Test
    void elRestoDeLaApiExigeAutenticacionEnModoSeguro() throws Exception {
        mockMvc.perform(get("/api/v1/stations")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/stations")).andExpect(status().isUnauthorized());
    }
}
