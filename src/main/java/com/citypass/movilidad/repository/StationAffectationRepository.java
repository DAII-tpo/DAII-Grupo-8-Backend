package com.citypass.movilidad.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Estado de afectaciones abiertas, confirmado en la misma transacción que el outbox. */
@Repository
public class StationAffectationRepository {
    private final JdbcTemplate jdbc;

    public StationAffectationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lock() {
        jdbc.queryForObject("SELECT id FROM station_affectation_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }

    public List<Affectation> findAll() {
        return jdbc.query("SELECT * FROM station_affectations ORDER BY station_id, cause_type, cause_id", (rs, row) ->
                new Affectation(rs.getString("affectation_id"), rs.getLong("station_id"),
                        rs.getString("cause_id"), rs.getString("cause_type"), rs.getString("origin_module"),
                        rs.getString("correlation_id")));
    }

    public void save(Affectation a) {
        jdbc.update("""
                INSERT INTO station_affectations
                (affectation_id, station_id, cause_id, cause_type, origin_module, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, a.id(), a.stationId(), a.causeId(), a.causeType(), a.originModule(), a.correlationId());
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM station_affectations WHERE affectation_id = ?", id);
    }

    public record Key(long stationId, String causeType, String causeId) { }
    public record Affectation(String id, long stationId, String causeId, String causeType,
                              String originModule, String correlationId) {
        public Key key() { return new Key(stationId, causeType, causeId); }
    }
}
