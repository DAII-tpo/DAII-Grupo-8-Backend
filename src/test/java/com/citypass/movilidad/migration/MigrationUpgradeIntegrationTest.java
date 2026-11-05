package com.citypass.movilidad.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises Flyway itself, including the upgrade from the migration already on main. */
@Testcontainers
class MigrationUpgradeIntegrationTest {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("movilidad").withUsername("test").withPassword("test");

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .cleanDisabled(false).target(target).load();
    }

    @BeforeEach
    void resetDatabase() {
        flyway("latest").clean();
    }

    @Test
    void reproduceElErrorDeResolverDosMigracionesConVersionSeis(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("V6__bike_emergencies.sql"), "SELECT 1;");
        Files.writeString(directory.resolve("V6__cu_int_01_destinos_incidentes.sql"), "SELECT 1;");
        Flyway duplicate = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("filesystem:" + directory).load();
        assertThatThrownBy(duplicate::migrate).isInstanceOf(FlywayException.class)
                .hasMessageContaining("Found more than one migration with version 6");
    }

    @Test
    void actualizaMainV6ACuInt01SinPerderDatosNiCambiarMigracionesAplicadas() {
        Flyway previous = flyway("6");
        previous.migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        Integer checksum = jdbc.queryForObject(
                "SELECT checksum FROM flyway_schema_history WHERE version = '6'", Integer.class);
        assertThat(previous.info().current().getDescription()).isEqualTo("bike emergencies");
        jdbc.update("INSERT INTO event_outbox (id, aggregate_type, aggregate_id, event_type, payload) "
                + "VALUES (UUID(), 'TRIP', 'existing-trip', 'com.citypass.movilidad.ViajeIniciado', '{}')");

        Flyway current = flyway("latest");
        var result = current.migrate();
        assertThat(result.migrationsExecuted).isEqualTo(7);
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("13");
        assertThat(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version = '6'",
                Integer.class)).isEqualTo(checksum);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE aggregate_id = 'existing-trip'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bike_emergencies", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cultural_destinations", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbound_entity_states", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM destination_recommendation_jobs", Integer.class)).isZero();
        jdbc.update("INSERT INTO event_outbox (id, aggregate_type, aggregate_id, event_type, payload) "
                + "VALUES (UUID(), 'CULTURAL_EVENT', 'new-event', 'com.citypass.movilidad.RecomendacionDestinoActualizada', '{}')");
        current.validate();
        assertThat(current.migrate().migrationsExecuted).isZero();
    }
    @Test
    void actualizaLaBaseV12DeRenderSinPerderDestinosNiSolicitudes() {
        flyway("12").migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        jdbc.update("""
                INSERT INTO cultural_destinations
                (event_id,space_id,title,starts_at,ends_at,max_capacity,registrations,full_capacity,cancelled,space_enabled,updated_at)
                VALUES ('activo','sp','A',NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY),20,3,false,false,true,NOW()),
                       ('completo','sp','B',NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY),20,20,true,false,true,NOW()),
                       ('cancelado','sp','C',NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY),20,4,false,true,true,NOW())
                """);
        jdbc.update("INSERT INTO destination_recommendation_jobs (event_id,request_token) VALUES ('activo','token-previo')");
        assertThat(flyway("latest").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_status FROM cultural_destinations ORDER BY event_id", String.class))
                .containsExactly("UNKNOWN", "CANCELLED", "ACTIVE_FULL");
        assertThat(jdbc.queryForObject("SELECT request_token FROM destination_recommendation_jobs WHERE event_id='activo'", String.class))
                .isEqualTo("token-previo");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM destination_recommendation_jobs WHERE pending=TRUE", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT recommendation_token FROM cultural_destinations WHERE event_id='activo'", String.class))
                .isEqualTo("token-previo");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM espacios_event_cursors WHERE entity_type='LOCK'", Integer.class)).isEqualTo(1);
        assertThat(flyway("latest").migrate().migrationsExecuted).isZero();
    }
}
