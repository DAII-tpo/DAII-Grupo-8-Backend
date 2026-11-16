package com.citypass.movilidad;

import com.citypass.movilidad.client.RecommendationClient;
import com.citypass.movilidad.client.RecommendationUnavailableException;
import com.citypass.movilidad.client.dto.RecommendationApiRequest;
import com.citypass.movilidad.client.dto.RecommendationApiResponse;
import com.citypass.movilidad.dto.FestivalRestockTaskResponse;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import com.citypass.movilidad.model.enums.RecommendationStatus;
import com.citypass.movilidad.service.FestivalRestockService;
import com.citypass.movilidad.service.FestivalRestockService.EventTransferPlan;
import org.springframework.jdbc.core.JdbcTemplate;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

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
    private static final String TASKS_PATH = "/api/v1/admin/festival-restock-tasks";

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
    @MockitoBean RecommendationClient recommendation;

    @Test
    @Transactional
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

        mvc.perform(get("/api/v1/admin/festival-restock-tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].coverageMeters").value(300))
                .andExpect(jsonPath("$[0].correlationId").value("act-festival-houssay"))
                .andExpect(jsonPath("$[0].stationName").value("HOSPITAL DE CLINICAS"))
                .andExpect(jsonPath("$[1].stationName").value("PLAZA HOUSSAY"))
                .andExpect(jsonPath("$[2].stationName").value("FACULTAD DE MEDICINA"))
                .andExpect(jsonPath("$[0].resolution").value("STOCK_SUFFICIENT"))
                .andExpect(jsonPath("$[1].status").value("PENDING"))
                .andExpect(jsonPath("$[1].targetBikes").value(9));

        Long donor = stationId("PASTEUR");
        jdbc.update("UPDATE stations SET capacity = 20 WHERE id = ?", donor);
        for (int i = 0; i < 12; i++) addBike("FEST-DONOR-" + i, donor);
        mvc.perform(get("/api/v1/admin/festival-restock-tasks/transfer-plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].plannedBikes").value(2))
                .andExpect(jsonPath("$[0].transfers.length()").value(2))
                .andExpect(jsonPath("$[0].transfers[0].bikes").value(1))
                .andExpect(jsonPath("$[0].transfers[1].bikes").value(1))
                .andExpect(jsonPath("$[0].transfers[0].source.totalBikes").value(12))
                .andExpect(jsonPath("$[0].transfers[0].source.minimumBikes").value(10));

        jdbc.update("""
                INSERT INTO stations (external_id, name, latitude, longitude, capacity, status, source)
                VALUES ('FEST-MODEL', 'DONANTE MODELO', -34.6050, -58.4040, 10, 'ACTIVE', 'MANUAL')
                """);
        long modelDonor = stationId("DONANTE MODELO");
        for (int i = 0; i < 7; i++) addBike("FEST-MODEL-" + i, modelDonor);
        given(recommendation.recommend(any())).willAnswer(invocation -> {
            RecommendationApiRequest request = invocation.getArgument(0);
            assertThat(request.purpose()).isEqualTo(RecommendationPurpose.PICKUP);
            assertThat(request.stations())
                    .allSatisfy(station -> assertThat(station.availableBikes()).isEqualTo(2));
            return new RecommendationApiResponse(RecommendationStatus.RECOMMENDED, null,
                    RecommendationPurpose.PICKUP, "qa", null, List.of(), null, List.of(
                    new RecommendationApiResponse.RankedStation(modelDonor, 1),
                    new RecommendationApiResponse.RankedStation(donor, 2)));
        });
        mvc.perform(get("/api/v1/admin/festival-restock-tasks/transfer-plan").param("useModel", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].plannedBikes").value(4))
                .andExpect(jsonPath("$[0].strategy").value("MODEL"))
                .andExpect(jsonPath("$[0].transfers[0].source.stationId").value(modelDonor));

        willThrow(new RecommendationUnavailableException("QA")).given(recommendation).recommend(any());
        mvc.perform(get("/api/v1/admin/festival-restock-tasks/transfer-plan").param("useModel", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].plannedBikes").value(4))
                .andExpect(jsonPath("$[0].strategy").value("MODEL_UNAVAILABLE"))
                .andExpect(jsonPath("$[0].transfers[0].source.stationId").value(donor));

        addBike("FEST-EXTRA", pending);
        jdbc.update("UPDATE festival_restock_tasks SET deadline_epoch_ms = ? WHERE station_id = ?",
                Instant.now().minusSeconds(30).toEpochMilli(), expired);
        restock.reconcile();
        mvc.perform(get("/api/v1/admin/festival-restock-tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$[1].resolution").value("STOCK_SUFFICIENT"))
                .andExpect(jsonPath("$[2].resolution").value("TARGET_REACHED"))
                .andExpect(jsonPath("$[2].resolvedAt").isNotEmpty());
    }

    @Test
    @Transactional
    void higherCapacityIncludesAnAdditionalStation() throws Exception {
        jdbc.update("""
                INSERT INTO stations (external_id, name, latitude, longitude, capacity, status, source)
                VALUES ('FEST-COVERAGE', 'ESTACION A 400 M', -34.6046, -58.5500, 10, 'ACTIVE', 'MANUAL')
                """);
        var small = festival("festival-300m", 2499);
        small.putObject("ubicacion").put("lat", -34.6010).put("lng", -58.5500);
        var large = festival("festival-500m", 2500);
        large.putObject("ubicacion").put("lat", -34.6010).put("lng", -58.5500);
        send(small).andExpect(status().isNoContent());
        send(large).andExpect(status().isNoContent());

        var coverage = jdbc.queryForList("""
                SELECT t.activity_id, t.coverage_meters FROM festival_restock_tasks t
                JOIN stations s ON t.station_id = s.id WHERE s.external_id = 'FEST-COVERAGE'
                """);
        jdbc.update("DELETE FROM festival_restock_tasks WHERE activity_id IN ('festival-300m', 'festival-500m')");
        assertThat(coverage).containsExactly(java.util.Map.of("activity_id", "festival-500m", "coverage_meters", 500));
    }

    @Test
    @Transactional
    void completedStationCanDonateOnlyAboveItsHighestActiveTarget() throws Exception {
        jdbc.update("""
                INSERT INTO stations (external_id, name, latitude, longitude, capacity, status, source)
                VALUES ('FEST-INTERNAL-DONOR', 'DONANTE DEL EVENTO', -34.6650, -58.5500, 20, 'ACTIVE', 'MANUAL'),
                       ('FEST-INTERNAL-RECIPIENT', 'DESTINO DEL EVENTO', -34.6660, -58.5500, 10, 'ACTIVE', 'MANUAL')
                """);
        long donor = stationId("DONANTE DEL EVENTO");
        for (int i = 0; i < 19; i++) addBike("FEST-INTERNAL-" + i, donor);

        var first = festival("festival-internal-a", 1000);
        first.putObject("ubicacion").put("lat", -34.6650).put("lng", -58.5500);
        send(first).andExpect(status().isNoContent());
        jdbc.update("UPDATE festival_restock_tasks SET target_bikes = 16 WHERE activity_id = ? AND station_id = ?",
                "festival-internal-a", donor);

        var second = festival("festival-internal-b", 1000);
        second.putObject("ubicacion").put("lat", -34.6650).put("lng", -58.5500);
        send(second).andExpect(status().isNoContent());

        assertDonorAllowance(18, 1);
        jdbc.update("UPDATE festival_restock_tasks SET deadline_epoch_ms = ? WHERE activity_id = ?",
                Instant.now().minusSeconds(30).toEpochMilli(), "festival-internal-b");
        assertDonorAllowance(16, 3);
    }

    private void assertDonorAllowance(int minimum, int expectedBikes) throws Exception {
        var response = mvc.perform(get("/api/v1/admin/festival-restock-tasks/transfer-plan"))
                .andExpect(status().isOk()).andReturn().getResponse();
        List<EventTransferPlan> plans = json.readerForListOf(EventTransferPlan.class)
                .readValue(response.getContentAsString());
        var plan = plans.stream().filter(event -> "festival-internal-a".equals(event.activityId()))
                .findFirst().orElseThrow();
        assertThat(plan.plannedBikes()).isEqualTo(expectedBikes);
        var source = plan.transfers().getFirst().source();
        assertThat(source.stationName()).isEqualTo("DONANTE DEL EVENTO");
        assertThat(source.minimumBikes()).isEqualTo(minimum);
        assertThat(source.transferableBikes()).isEqualTo(expectedBikes);
    }

    @Test
    @Transactional
    void activityChangesPausePlanningAndIgnoreOlderUpdates() throws Exception {
        String id = "festival-status-qa";
        String path = "/api/v1/admin/festival-restock-tasks";
        var activity = festival(id, 1000).put("titulo", "QA - Avisos de horario");
        var location = activity.putObject("ubicacion");
        location.put("lat", -34.645);
        location.put("lng", -58.5500);
        jdbc.update("""
                INSERT INTO stations (external_id, name, latitude, longitude, capacity, status, source)
                VALUES ('FEST-STATUS', 'ESTACION ESTADO', -34.645, -58.5500, 10, 'ACTIVE', 'MANUAL')
                """);

        long now = Instant.now().toEpochMilli();
        // Espacios puede publicar en tópicos distintos: el cambio puede llegar antes que la creación.
        sendChange(id, "SUSPENDIDA", now);
        send(activity).andExpect(status().isNoContent());
        long taskId = jdbc.queryForObject("""
                SELECT t.id FROM festival_restock_tasks t JOIN stations s ON s.id = t.station_id
                WHERE t.activity_id = ? AND s.external_id = 'FEST-STATUS'
                """, Long.class, id);
        var response = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse();
        List<FestivalRestockTaskResponse> rows = json.readerForListOf(FestivalRestockTaskResponse.class)
                .readValue(response.getContentAsString());
        var task = rows.stream().filter(row -> row.id() == taskId).findFirst().orElseThrow();
        assertThat(task.activityChange().state()).isEqualTo("SUSPENDIDA");
        assertThat(task.activityChange().reason()).isEqualTo("Motivo de prueba");
        assertActivityPlan(id, false);
        mvc.perform(patch(path + "/" + taskId + "/complete")).andExpect(status().isConflict());
        mvc.perform(patch(path + "/" + taskId + "/target")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetBikes\":1}"))
                .andExpect(status().isConflict());

        sendChange(id, "PROGRAMADA", now + 1);
        sendChange(id, "CANCELADA", now);
        assertActivityPlan(id, true);

        long originalDeadline = activity.path("fechaHoraFinalizacion").asLong();
        var extension = json.createObjectNode().put("actividadId", id)
                .put("fechaHoraFinalizacion", originalDeadline + 3_600_000);
        String deadlinePath = "/api/v1/dev/festival/horario-modificado";
        mvc.perform(post(deadlinePath).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(extension)))
                .andExpect(status().isNoContent());
        var updated = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse();
        List<FestivalRestockTaskResponse> updatedRows = json.readerForListOf(FestivalRestockTaskResponse.class)
                .readValue(updated.getContentAsString());
        assertThat(updatedRows).filteredOn(row -> id.equals(row.activityId())).isNotEmpty().allSatisfy(row -> {
            assertThat(row.deadlineChangedAt()).isNotNull();
            assertThat(row.previousDeadline()).isEqualTo(Instant.ofEpochMilli(originalDeadline));
            assertThat(row.deadline()).isEqualTo(Instant.ofEpochMilli(originalDeadline + 3_600_000));
            assertThat(row.activityChange().state()).isEqualTo("PROGRAMADA");
        });

        sendChange(id, "CANCELADA", now + 2);
        extension.put("fechaHoraFinalizacion", originalDeadline + 7_200_000);
        mvc.perform(post(deadlinePath).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(extension)))
                .andExpect(status().isConflict());
        assertActivityPlan(id, false);
    }

    @Test
    @Transactional
    void movingQaActivityReplansCoverageAndEarlierClosingExpiresPendingTasks() throws Exception {
        jdbc.update("""
                INSERT INTO stations (external_id, name, latitude, longitude, capacity, status, source)
                VALUES ('QA-MOVE-OLD', 'ANTES', -34.6500, -58.5500, 10, 'ACTIVE', 'MANUAL'),
                       ('QA-MOVE-NEW', 'DESPUES', -34.6600, -58.5500, 10, 'ACTIVE', 'MANUAL')
                """);
        String id = "qa-move-and-close";
        long now = Instant.now().toEpochMilli();
        var activity = festival(id, 1100).put("titulo", "QA - Mover y cerrar")
                .put("fechaHoraInicio", now - 7_200_000).put("fechaHoraFinalizacion", now + 3_600_000);
        activity.putObject("ubicacion").put("lat", -34.6500).put("lng", -58.5500);
        send(activity).andExpect(status().isNoContent());

        var move = json.createObjectNode().put("actividadId", id).put("lat", -34.6600).put("lng", -58.5500);
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/dev/festival/ubicacion-modificada").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(move))).andExpect(status().isNoContent());
        }
        var movedTasks = restock.findUpcoming().stream().filter(task -> id.equals(task.activityId())).toList();
        assertThat(movedTasks).hasSize(2);
        assertThat(movedTasks).filteredOn(task -> "ANTES".equals(task.stationName())).singleElement()
                .satisfies(task -> assertThat(task.resolution()).isEqualTo("OUTSIDE_COVERAGE"));
        assertThat(movedTasks).filteredOn(task -> "DESPUES".equals(task.stationName())).singleElement()
                .satisfies(task -> assertThat(task.status()).isEqualTo("PENDING"));

        var close = json.createObjectNode().put("actividadId", id).put("fechaHoraFinalizacion", now - 3_600_000);
        mvc.perform(post("/api/v1/dev/festival/horario-modificado").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(close))).andExpect(status().isNoContent());
        assertThat(restock.findUpcoming()).filteredOn(task -> id.equals(task.activityId())
                && "DESPUES".equals(task.stationName())).singleElement()
                .satisfies(task -> assertThat(task.resolution()).isEqualTo("EXPIRED"));
        assertActivityPlan(id, false);
    }

    private void assertActivityPlan(String id, boolean expected) throws Exception {
        var response = mvc.perform(get("/api/v1/admin/festival-restock-tasks/transfer-plan"))
                .andExpect(status().isOk()).andReturn().getResponse();
        List<EventTransferPlan> plans = json.readerForListOf(EventTransferPlan.class)
                .readValue(response.getContentAsString());
        assertThat(plans.stream().anyMatch(plan -> id.equals(plan.activityId()))).isEqualTo(expected);
    }

    private void sendChange(String id, String state, long modifiedAt) throws Exception {
        var change = json.createObjectNode().put("actividadId", id).put("estadoNuevo", state)
                .put("motivo", "Motivo de prueba").put("modificadaEn", modifiedAt);
        mvc.perform(post("/api/v1/dev/festival/actividad-modificada")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(change)))
                .andExpect(status().isNoContent());
    }

    @Test
    @Transactional
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
        String taskPath = TASKS_PATH + "/" + taskId;

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
        mvc.perform(patch(TASKS_PATH + "/-1/complete")).andExpect(status().isNotFound());
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
