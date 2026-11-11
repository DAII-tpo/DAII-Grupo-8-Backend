package com.citypass.movilidad.analytics;

import com.citypass.movilidad.support.FederatedSecurityTestConfig;
import com.citypass.movilidad.support.TestSecrets;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {"security.local.enabled=false", TestSecrets.CITIZEN_AUTH_SECRET_PROPERTY,
        "movilidad.emergency.deadline-check-interval=1h"})
@Import(FederatedSecurityTestConfig.class)
class AnalyticsAccessIntegrationTest {
    private static final String PATH = "/api/v1/admin/analytics/embed";
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("movilidad").withUsername("test").withPassword("test");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean AnalyticsEmbedClient client;
    @BeforeEach void response() {
        when(client.fetch()).thenReturn(new AnalyticsEmbedClient.Embed(MediaType.APPLICATION_JSON, "{\"status\":\"ok\"}"));
    }
    @Test void adminConFirmaRealPuedeConsultar() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer " + FederatedSecurityTestConfig.TOKENS.signedToken("U-BI-1", List.of("admin"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        verify(client).fetch();
    }
    @Test void empleadoSinPermisoDa403() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer " + FederatedSecurityTestConfig.TOKENS.signedToken("U-BI-2", List.of("operadores"))))
                .andExpect(status().isForbidden());
        verify(client, never()).fetch();
    }
    @Test void ciudadanoRegistradoConJwtValidoDa403() throws Exception {
        String body = "{\"email\":\"bi-" + UUID.randomUUID() + "@example.com\",\"password\":\"" + TestSecrets.CITIZEN_PASSWORD
                + "\",\"firstName\":\"Prueba\",\"lastName\":\"BI\"}";
        String response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(get(PATH).header("Authorization", "Bearer " + mapper.readTree(response).path("accessToken").asText()))
                .andExpect(status().isForbidden());
        verify(client, never()).fetch();
    }
    @Test void sinTokenOConTokenInvalidoDa401() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        verify(client, never()).fetch();
    }
}
