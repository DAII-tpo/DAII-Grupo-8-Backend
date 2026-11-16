package com.citypass.movilidad.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class TiDbMigrationIntegrationTest {
    @Container
    static GenericContainer<?> tidb = new GenericContainer<>(DockerImageName.parse("pingcap/tidb:v8.5.3"))
            .withCommand("--store=unistore", "--path=/tmp/tidb", "--host=0.0.0.0")
            .withExposedPorts(4000, 10080)
            .waitingFor(Wait.forHttp("/status").forPort(10080))
            .withStartupTimeout(Duration.ofMinutes(2));

    @Test
    void migraDesdeCeroYValidaElHistorialEnTiDb853() {
        String url = databaseUrl("test");
        Flyway flyway = Flyway.configure().dataSource(url, "root", "").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(32);
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).contains("TiDB-v8.5.3");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM neighborhoods", Integer.class)).isEqualTo(48);
        jdbc.update("INSERT INTO event_outbox (id,aggregate_type,aggregate_id,event_type,payload) "
                + "VALUES (UUID(),'CLIMATE_ALERT','prueba-clima','com.citypass.movilidad.AlertaClimaticaAplicada','{}')");
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void actualizaDesdeMainV18ConservandoDatosYChecksums() {
        var admin = new JdbcTemplate(new DriverManagerDataSource(databaseUrl("test"), "root", ""));
        admin.execute("CREATE DATABASE migration_upgrade");
        String url = databaseUrl("migration_upgrade");
        Flyway.configure().dataSource(url, "root", "").target("18").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
        var history = jdbc.queryForList("SELECT version, script, checksum FROM flyway_schema_history ORDER BY installed_rank");
        jdbc.update("""
                INSERT INTO stations (id,name,latitude,longitude,capacity,status,source,external_id)
                VALUES (1000,'MADERO UCA',-34.6,-58.4,20,'ACTIVE','DATASET','ecobici-228-10')
                """);

        Flyway current = Flyway.configure().dataSource(url, "root", "").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(14);
        assertThat(jdbc.queryForList("SELECT version, script, checksum FROM flyway_schema_history WHERE CAST(version AS UNSIGNED) <= 18 ORDER BY installed_rank"))
                .isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT name FROM stations WHERE id=1000", String.class)).isEqualTo("MADERO UCA");
        assertThat(jdbc.queryForObject("SELECT neighborhood_id FROM stations WHERE id=1000", String.class)).isEqualTo("AR-CABA-PUERTO-MADERO");
        current.validate();
        assertThat(current.migrate().migrationsExecuted).isZero();
    }

    private static String databaseUrl(String database) {
        return "jdbc:mysql://" + tidb.getHost() + ":" + tidb.getMappedPort(4000)
                + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true";
    }
}
