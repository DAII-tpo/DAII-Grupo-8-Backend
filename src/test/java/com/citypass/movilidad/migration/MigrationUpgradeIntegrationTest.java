package com.citypass.movilidad.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises Flyway itself, including the upgrade from the migration already on main. */
@Testcontainers
class MigrationUpgradeIntegrationTest {
    @TempDir Path directory;
    private Path integrated;
    private Path legacy;
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("movilidad").withUsername("test").withPassword("test");

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .cleanDisabled(false).target(target).load();
    }

    private Flyway flyway(Path location, String target) {
        return Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("filesystem:" + location).cleanDisabled(false).target(target).load();
    }

    @BeforeEach
    void resetDatabase() throws IOException {
        flyway("latest").clean();
        integrated = Files.createDirectory(directory.resolve("integrated"));
        legacy = Files.createDirectory(directory.resolve("legacy"));
        var resolver = new PathMatchingResourcePatternResolver();
        // El snapshot de Login se usa sin modificarlo. En el árbol integrado se exige igualdad byte a byte.
        for (String pattern : List.of("classpath*:db/migration/V*__*.sql", "classpath*:db/main-login-v9/V*__*.sql")) {
            for (var resource : resolver.getResources(pattern)) {
                String name = resource.getFilename();
                byte[] sql = resource.getContentAsByteArray();
                Path target = integrated.resolve(name);
                if (Files.exists(target)) assertThat(Files.readAllBytes(target)).isEqualTo(sql);
                else Files.write(target, sql);
                int version = Integer.parseInt(name.substring(1, name.indexOf("__")));
                if (version <= 6 || version >= 10) {
                    String oldName = version <= 6 ? name : name.replaceFirst("V" + version + "__", "V" + (version - 3) + "__");
                    Files.write(legacy.resolve(oldName), sql);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 8, 9})
    void reproduceLasColisionesDelPr36(int version) throws Exception {
        Path duplicateDirectory = Files.createDirectory(directory.resolve("duplicate"));
        for (Path source : List.of(integrated, legacy)) {
            try (var files = Files.newDirectoryStream(source, "V" + version + "__*.sql")) {
                for (Path file : files) Files.copy(file, duplicateDirectory.resolve(file.getFileName()));
            }
        }
        Flyway duplicate = flyway(duplicateDirectory, "latest");
        assertThatThrownBy(duplicate::migrate).isInstanceOf(FlywayException.class)
                .hasMessageContaining("Found more than one migration with version " + version);
    }

    @Test
    void actualizaMainV6ACuInt01SinPerderDatosNiCambiarMigracionesAplicadas() {
        Flyway previous = flyway(integrated, "6");
        previous.migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        Integer checksum = jdbc.queryForObject(
                "SELECT checksum FROM flyway_schema_history WHERE version = '6'", Integer.class);
        assertThat(previous.info().current().getDescription()).isEqualTo("bike emergencies");
        jdbc.update("INSERT INTO event_outbox (id, aggregate_type, aggregate_id, event_type, payload) "
                + "VALUES (UUID(), 'TRIP', 'existing-trip', 'com.citypass.movilidad.ViajeIniciado', '{}')");

        Flyway current = flyway(integrated, "latest");
        var result = current.migrate();
        assertThat(result.migrationsExecuted).isEqualTo(10);
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("16");
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
    void actualizaV15AV16SinPerderDestinosNiSolicitudes() {
        flyway("15").migrate();
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

    @Test
    void actualizaMainConLoginV9SinModificarSusMigracionesNiDatos() {
        flyway(integrated, "9").migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        var history = jdbc.queryForList("SELECT version, script, checksum FROM flyway_schema_history ORDER BY installed_rank");
        jdbc.update("INSERT INTO event_outbox (id,aggregate_type,aggregate_id,event_type,payload) "
                + "VALUES (UUID(),'TRIP','previo-login','com.citypass.movilidad.ViajeIniciado','{}')");
        var current = flyway(integrated, "latest");
        assertThat(current.migrate().migrationsExecuted).isEqualTo(7);
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("16");
        assertThat(jdbc.queryForList("SELECT version, script, checksum FROM flyway_schema_history WHERE CAST(version AS UNSIGNED) <= 9 ORDER BY installed_rank"))
                .isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE aggregate_id='previo-login'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM espacios_event_cursors WHERE entity_type='LOCK'", Integer.class)).isEqualTo(1);
        current.validate();
        assertThat(current.migrate().migrationsExecuted).isZero();
    }

    @Test
    void rechazaElHistorialAntiguoDeRenderSinRepararloNiBorrarDatos() {
        flyway(legacy, "13").migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        var history = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        jdbc.update("INSERT INTO event_outbox (id,aggregate_type,aggregate_id,event_type,payload) "
                + "VALUES (UUID(),'TRIP','conservar','com.citypass.movilidad.ViajeIniciado','{}')");
        assertThatThrownBy(() -> flyway(integrated, "latest").migrate())
                .isInstanceOf(FlywayException.class).hasMessageContaining("Validate failed");
        assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank")).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE aggregate_id='conservar'", Integer.class)).isEqualTo(1);
    }
}
