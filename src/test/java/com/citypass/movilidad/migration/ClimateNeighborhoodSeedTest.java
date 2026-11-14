package com.citypass.movilidad.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ClimateNeighborhoodSeedTest {
    @Test
    void seedUsesAllUniqueEcobiciIdsAndDatasetNeighborhoodInKnownConflicts() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/V20__seed_caba_neighborhoods.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(sql.lines().filter(line -> line.startsWith("INSERT INTO neighborhoods")).count()).isEqualTo(48);
        assertThat(sql.lines().filter(line -> line.startsWith("UPDATE stations")).count()).isEqualTo(468);
        assertThat(sql).contains("neighborhood_id = 'AR-CABA-PUERTO-MADERO' WHERE external_id = 'ecobici-228-10'");
        assertThat(sql).contains("neighborhood_id = 'AR-CABA-SAN-NICOLAS' WHERE external_id = 'ecobici-8-8'");
        assertThat(sql).contains("neighborhood_id = 'AR-CABA-BOCA' WHERE external_id = 'ecobici-73-73'");
    }
}
