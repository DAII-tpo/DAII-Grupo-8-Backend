package com.citypass.movilidad.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Estado de afectaciones abiertas, confirmado en la misma transacción que el outbox. */
@Repository
public class StationAffectationRepository {
    private static final String FESTIVAL_PROGRAMMED = """
             t.removed_from_coverage_epoch_ms IS NULL AND t.deadline_epoch_ms > ?
               AND (a.activity_state IS NULL OR a.activity_state = 'PROGRAMADA')
            """;
    private static final String OPEN_FESTIVAL = """
            FROM station_affectations f
            JOIN festival_restock_tasks t ON t.activity_id = f.cause_id COLLATE utf8mb4_bin AND t.station_id = f.station_id
            LEFT JOIN festival_activity_changes a ON a.activity_id = t.activity_id
            WHERE f.cause_type = 'EVENTO_PUBLICO' AND
            """ + FESTIVAL_PROGRAMMED;
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

    public Optional<FestivalTransfer> findFestivalTransfer(long taskId, long stationId, long now) {
        return jdbc.query("""
                SELECT t.activity_id, t.correlation_id, t.start_epoch_ms, t.deadline_epoch_ms
                FROM festival_restock_tasks t
                LEFT JOIN festival_activity_changes a ON a.activity_id = t.activity_id
                WHERE t.id = ? AND t.station_id = ? AND t.resolution IS NULL AND
                """ + FESTIVAL_PROGRAMMED, (rs, row) -> new FestivalTransfer(
                rs.getString("activity_id"), rs.getString("correlation_id"),
                rs.getObject("start_epoch_ms", Long.class), rs.getLong("deadline_epoch_ms")),
                taskId, stationId, now).stream().findFirst();
    }

    public boolean hasFestivalAffectation(long stationId, String activityId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM station_affectations
                              WHERE station_id = ? AND cause_type = 'EVENTO_PUBLICO' AND cause_id = ?)
                """, Boolean.class, stationId, activityId);
        return Boolean.TRUE.equals(exists);
    }

    public Set<String> openFestivalAffectations(long now) {
        return new HashSet<>(jdbc.queryForList("SELECT f.affectation_id " + OPEN_FESTIVAL, String.class, now));
    }

    public Set<Long> festivalPickupRestrictions(long now) {
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT f.station_id " + OPEN_FESTIVAL
                + " AND (t.start_epoch_ms IS NULL OR t.start_epoch_ms <= ?)", Long.class, now, now));
    }

    public record FestivalTransfer(String activityId, String correlationId, Long startMillis, long deadlineMillis) {}

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
