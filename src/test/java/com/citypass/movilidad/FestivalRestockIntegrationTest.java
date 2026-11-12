package com.citypass.movilidad;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("festival-local")
@AutoConfigureMockMvc
@SpringBootTest
class FestivalRestockIntegrationTest {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("movilidad").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void plansOnlyEligibleUpcomingActivitiesAndDoesNotDuplicateTasks() throws Exception {
        var activity = festival("festival-houssay", 1000);
        send(activity).andExpect(status().isNoContent());
        send(activity).andExpect(status().isNoContent());
        send(festival("evento-chico", 999)).andExpect(status().isNoContent());

        var ended = festival("evento-finalizado", 5000);
        ended.put("fechaHoraInicio", Instant.now().minusSeconds(120).toEpochMilli());
        ended.put("fechaHoraFinalizacion", Instant.now().minusSeconds(60).toEpochMilli());
        send(ended).andExpect(status().isNoContent());

        var invalid = festival("evento-invalido", 5000);
        invalid.put("fechaHoraInicio", "2026-11-15T00:00:00Z");
        send(invalid).andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/admin/festival-restock-tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].correlationId").value("act-festival-houssay"))
                .andExpect(jsonPath("$[0].stationName").value("HOSPITAL DE CLINICAS"))
                .andExpect(jsonPath("$[1].stationName").value("PLAZA HOUSSAY"))
                .andExpect(jsonPath("$[2].stationName").value("FACULTAD DE MEDICINA"));
    }

    private ObjectNode festival(String id, int capacity) {
        Instant now = Instant.now();
        var payload = json.createObjectNode();
        payload.put("actividadId", id);
        payload.put("correlationId", "act-" + id);
        payload.put("titulo", "Festival de Primavera");
        payload.put("estado", "PROGRAMADA");
        payload.put("capacidad", capacity);
        payload.put("fechaHoraInicio", now.plusSeconds(60).toEpochMilli());
        payload.put("fechaHoraFinalizacion", now.plusSeconds(600).toEpochMilli());
        payload.putObject("ubicacion").put("lat", -34.5994).put("lng", -58.3988);
        return payload;
    }

    private ResultActions send(ObjectNode data) throws Exception {
        return mvc.perform(post("/api/v1/dev/festival/actividad-creada")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(data)));
    }
}
