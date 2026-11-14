package com.citypass.movilidad;

import com.citypass.movilidad.service.FestivalRestockService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("festival-local")
@AutoConfigureMockMvc
@SpringBootTest
class FestivalRestockIntegrationTest {
    private static final String TASKS = "/api/v1/admin/festival-restock-tasks";

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
    @Autowired JdbcTemplate jdbc;
    @Autowired FestivalRestockService restock;

    @AfterEach
    void removeAdminTestTasks() {
        jdbc.update("DELETE FROM festival_restock_tasks WHERE activity_id = ?", "festival-admin");
        jdbc.update("DELETE FROM stations WHERE name = ?", "QA FESTIVAL ADMIN");
    }

    @Test
    void plansOnlyEligibleUpcomingActivitiesAndDoesNotDuplicateTasks() throws Exception {
        Long stocked = stationId("HOSPITAL DE CLINICAS");
        Long pending = stationId("PLAZA HOUSSAY");
        Long expired = stationId("FACULTAD DE MEDICINA");
        jdbc.update("UPDATE stations SET capacity = 10 WHERE id IN (?, ?)", stocked, pending);
        for (int i = 0; i < 9; i++) addBike("FEST-STOCK-" + i, stocked);
        for (int i = 0; i < 8; i++) addBike("FEST-PENDING-" + i, pending);
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

        mvc.perform(get(TASKS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].correlationId").value("act-festival-houssay"))
                .andExpect(jsonPath("$[0].stationName").value("HOSPITAL DE CLINICAS"))
                .andExpect(jsonPath("$[1].stationName").value("PLAZA HOUSSAY"))
                .andExpect(jsonPath("$[2].stationName").value("FACULTAD DE MEDICINA"))
                .andExpect(jsonPath("$[0].resolution").value("STOCK_SUFFICIENT"))
                .andExpect(jsonPath("$[1].status").value("PENDING"))
                .andExpect(jsonPath("$[1].targetBikes").value(9));

        addBike("FEST-EXTRA", pending);
        jdbc.update("UPDATE festival_restock_tasks SET deadline_epoch_ms = ? WHERE station_id = ?",
                Instant.now().minusSeconds(30).toEpochMilli(), expired);
        restock.reconcile();
        mvc.perform(get(TASKS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$[1].resolution").value("STOCK_SUFFICIENT"))
                .andExpect(jsonPath("$[2].resolution").value("TARGET_REACHED"))
                .andExpect(jsonPath("$[2].resolvedAt").isNotEmpty());
        long expiredTaskId = jdbc.queryForObject("""
                SELECT id FROM festival_restock_tasks WHERE activity_id = ? AND station_id = ?
                """, Long.class, "festival-houssay", expired);
        mvc.perform(patch(TASKS + "/" + expiredTaskId + "/complete")).andExpect(status().isConflict());
    }

    @Test
    void editsTargetsAndCompletesTasksFromTheAdminApi() throws Exception {
        jdbc.update("""
                INSERT INTO stations (name, latitude, longitude, capacity, status, source)
                VALUES ('QA FESTIVAL ADMIN', -32, -56, 10, 'ACTIVE', 'MANUAL')
                """);
        var activity = festival("festival-admin", 1000);
        activity.putObject("ubicacion").put("lat", -32).put("lng", -56);
        send(activity).andExpect(status().isNoContent());
        long taskId = jdbc.queryForObject("""
                SELECT id FROM festival_restock_tasks WHERE activity_id = ? AND station_id = ?
                """, Long.class, "festival-admin", stationId("QA FESTIVAL ADMIN"));
        String taskPath = TASKS + "/" + taskId;

        mvc.perform(patch(taskPath + "/target").contentType(MediaType.APPLICATION_JSON).content("""
                {"targetBikes": 0}
                """)).andExpect(status().isBadRequest());
        mvc.perform(patch(taskPath + "/target").contentType(MediaType.APPLICATION_JSON).content("""
                {"targetBikes": 1000}
                """)).andExpect(status().isConflict());

        mvc.perform(patch(taskPath + "/target").contentType(MediaType.APPLICATION_JSON).content("""
                {"targetBikes": 8}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.targetBikes").value(8));

        mvc.perform(patch(taskPath + "/complete"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resolution").value("MANUAL"));
        mvc.perform(patch(taskPath + "/complete"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resolution").value("MANUAL"));
        mvc.perform(get(taskPath + "/suggestion"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(patch(taskPath + "/target").contentType(MediaType.APPLICATION_JSON).content("""
                {"targetBikes": 1}
                """)).andExpect(status().isConflict());
        mvc.perform(patch(TASKS + "/-1/complete")).andExpect(status().isNotFound());
    }

    private Long stationId(String name) {
        return jdbc.queryForObject("SELECT id FROM stations WHERE name = ?", Long.class, name);
    }

    private void addBike(String code, long station) {
        jdbc.update("INSERT INTO bikes (code, station_id, status) VALUES (?, ?, 'AVAILABLE')", code, station);
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
