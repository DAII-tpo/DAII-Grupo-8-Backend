package com.citypass.movilidad.repository;

import com.citypass.movilidad.dto.FestivalRestockTaskResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class FestivalRestockTaskRepository {
    private final JdbcTemplate jdbc;

    public FestivalRestockTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String STOCK = """
            (SELECT COUNT(*) FROM bikes b
             WHERE b.station_id = t.station_id AND b.deleted_at IS NULL AND b.status = 'AVAILABLE')
            """;
    private static final String SELECT = """
            SELECT t.*, s.name AS station_name, s.capacity,
                   """ + STOCK + """
                   AS available_bikes,
                   GREATEST(0, s.capacity - (SELECT COUNT(*) FROM bikes b
                       WHERE b.station_id = t.station_id AND b.deleted_at IS NULL)) AS available_slots
            FROM festival_restock_tasks t JOIN stations s ON s.id = t.station_id
            """;

    public void createIfAbsent(String activityId, String correlationId, String title, long deadlineMillis,
                               long stationId, int distanceMeters, int target, Long coveredAt) {
        jdbc.update("""
                INSERT INTO festival_restock_tasks
                    (activity_id, correlation_id, activity_title, deadline_epoch_ms, station_id, distance_meters,
                     target_bikes, resolution, resolved_epoch_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, activityId, correlationId, title, deadlineMillis, stationId, distanceMeters,
                target, coveredAt == null ? null : "STOCK_SUFFICIENT", coveredAt);
    }

    public void reconcile(long now) {
        jdbc.update("""
                UPDATE festival_restock_tasks t JOIN stations s ON s.id = t.station_id
                SET t.resolution = 'TARGET_REACHED', t.resolved_epoch_ms = ?
                WHERE t.resolution IS NULL AND t.deadline_epoch_ms > ?
                  AND s.status = 'ACTIVE' AND s.deleted_at IS NULL AND
                """ + STOCK + " >= t.target_bikes", now, now);
        jdbc.update("""
                UPDATE festival_restock_tasks SET resolution = 'EXPIRED', resolved_epoch_ms = deadline_epoch_ms
                WHERE resolution IS NULL AND deadline_epoch_ms <= ?
                """, now);
    }

    public List<FestivalRestockTaskResponse> findAll(long now) {
        return query(now, " ORDER BY t.deadline_epoch_ms, t.activity_id, t.distance_meters, t.station_id");
    }

    public Optional<FestivalRestockTaskResponse> find(long id, long now) {
        return query(now, " WHERE t.id = ?", id).stream().findFirst();
    }

    private List<FestivalRestockTaskResponse> query(long now, String clause, Object... args) {
        return jdbc.query(SELECT + clause, (rs, row) -> {
            long deadline = rs.getLong("deadline_epoch_ms");
            String reason = rs.getString("resolution");
            Long resolved = rs.getObject("resolved_epoch_ms", Long.class);
            if (reason == null && deadline <= now) {
                reason = "EXPIRED";
                resolved = deadline;
            }
            String status = reason == null ? "PENDING" : "EXPIRED".equals(reason) ? "EXPIRED" : "COMPLETED";
            return new FestivalRestockTaskResponse(
                    rs.getLong("id"), rs.getString("activity_id"), rs.getString("correlation_id"),
                    rs.getString("activity_title"), rs.getLong("station_id"), rs.getString("station_name"),
                    rs.getInt("distance_meters"), Instant.ofEpochMilli(deadline),
                    rs.getInt("capacity"), rs.getInt("available_bikes"), rs.getInt("available_slots"),
                    rs.getInt("target_bikes"), status, reason, resolved == null ? null : Instant.ofEpochMilli(resolved));
        }, args);
    }

    public int updateTarget(long id, int target, long now) {
        return jdbc.update("""
                UPDATE festival_restock_tasks t JOIN stations s ON s.id = t.station_id
                SET t.target_bikes = ?
                WHERE t.id = ? AND t.resolution IS NULL AND t.deadline_epoch_ms > ?
                  AND s.deleted_at IS NULL AND s.status = 'ACTIVE' AND s.capacity >= ?
                """, target, id, now, target);
    }

    public int completeManually(long id, long now) {
        return jdbc.update("""
                UPDATE festival_restock_tasks
                SET resolution = 'MANUAL', resolved_epoch_ms = ?
                WHERE id = ? AND resolution IS NULL AND deadline_epoch_ms > ?
                """, now, id, now);
    }

    public List<Long> stationsWithUpcomingTasks(long now) {
        return jdbc.queryForList("""
                SELECT DISTINCT station_id FROM festival_restock_tasks
                WHERE deadline_epoch_ms > ?
                """, Long.class, now);
    }
}
