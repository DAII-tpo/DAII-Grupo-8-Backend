package com.citypass.movilidad.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/** Se usa dentro de la transacción del inbox o del writer de recomendaciones. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class EspaciosEventCursorRepository {
    private final JdbcTemplate jdbc;
    public EspaciosEventCursorRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lock() {
        jdbc.queryForObject("SELECT external_id FROM espacios_event_cursors WHERE entity_type='LOCK' "
                + "AND external_id='ESPACIOS' FOR UPDATE", String.class);
    }

    /** A igual receivedAt se conserva el orden de entrega; no se inventa una versión del productor. */
    public boolean accept(String type, String id, Instant receivedAt) {
        var previous = jdbc.queryForList("SELECT received_at FROM espacios_event_cursors "
                + "WHERE entity_type=? AND external_id=?", String.class, type, id);
        if (!previous.isEmpty() && previous.getFirst() != null
                && receivedAt.isBefore(Instant.parse(previous.getFirst()))) return false;
        jdbc.update("""
                INSERT INTO espacios_event_cursors (entity_type, external_id, received_at) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE received_at=VALUES(received_at)
                """, type, id, receivedAt.toString());
        return true;
    }
}
