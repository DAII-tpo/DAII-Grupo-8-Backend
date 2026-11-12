package com.citypass.movilidad.repository;

import com.citypass.movilidad.dto.FestivalRestockTaskResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class FestivalRestockTaskRepository {
    private final JdbcTemplate jdbc;

    public FestivalRestockTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void createIfAbsent(String activityId, String correlationId, String title, long deadlineMillis,
                               long stationId, int distanceMeters) {
        // El inbox cubre reentregas del mismo mensaje. La clave única cubre republicaciones con otro eventId.
        jdbc.update("""
                INSERT INTO festival_restock_tasks
                    (activity_id, correlation_id, activity_title, deadline_epoch_ms, station_id, distance_meters)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, activityId, correlationId, title, deadlineMillis, stationId, distanceMeters);
    }

    public List<FestivalRestockTaskResponse> findUpcoming(Instant now) {
        return jdbc.query("""
                SELECT t.id, t.activity_id, t.correlation_id, t.activity_title, t.station_id,
                       s.name AS station_name, t.distance_meters, t.deadline_epoch_ms
                FROM festival_restock_tasks t
                JOIN stations s ON s.id = t.station_id
                WHERE t.deadline_epoch_ms > ? AND s.deleted_at IS NULL AND s.status = 'ACTIVE'
                ORDER BY t.deadline_epoch_ms, t.activity_id, t.distance_meters, t.station_id
                """, (rs, row) -> new FestivalRestockTaskResponse(
                rs.getLong("id"), rs.getString("activity_id"), rs.getString("correlation_id"),
                rs.getString("activity_title"), rs.getLong("station_id"), rs.getString("station_name"),
                rs.getInt("distance_meters"), Instant.ofEpochMilli(rs.getLong("deadline_epoch_ms"))),
                now.toEpochMilli());
    }
}
