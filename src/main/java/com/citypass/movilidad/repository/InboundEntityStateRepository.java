package com.citypass.movilidad.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable projection shared by topics whose delivery order is not guaranteed.
 * Runs in the dispatcher's transaction, together with inbox and domain changes.
 * Without producer sequence numbers, terminal cancellation/closure wins forever
 * for the same external ID; reopening requires a separately agreed contract.
 */
@Repository
public class InboundEntityStateRepository {
    private final JdbcTemplate jdbc;

    public InboundEntityStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void remember(String type, String id, String state) {
        jdbc.update("""
                INSERT INTO inbound_entity_states (entity_type, external_id, state_value) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE state_value = VALUES(state_value)
                """, type, id, state);
    }

    public boolean hasState(String type, String id, String state) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inbound_entity_states
                WHERE entity_type = ? AND external_id = ? AND state_value = ?
                """, Integer.class, type, id, state);
        return count != null && count > 0;
    }
}
