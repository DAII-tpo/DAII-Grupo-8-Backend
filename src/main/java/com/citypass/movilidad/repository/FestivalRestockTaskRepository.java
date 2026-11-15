package com.citypass.movilidad.repository;

import com.citypass.movilidad.dto.FestivalRestockTaskResponse;
import com.citypass.movilidad.dto.FestivalRestockTaskResponse.ActivityChange;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class FestivalRestockTaskRepository {
    private final JdbcTemplate jdbc;

    public FestivalRestockTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String EXPIRED = "EXPIRED";
    private static final String OUTSIDE_COVERAGE = "OUTSIDE_COVERAGE";
    private static final String ORDER_BY_TASK = " ORDER BY t.deadline_epoch_ms, t.activity_id, t.distance_meters, t.station_id";
    private static final String STOCK = """
            (SELECT COUNT(*) FROM bikes b
             WHERE b.station_id = t.station_id AND b.deleted_at IS NULL AND b.status = 'AVAILABLE')
            """;
    private static final String TOTAL = """
            (SELECT COUNT(*) FROM bikes b WHERE b.station_id = t.station_id AND b.deleted_at IS NULL)
            """;
    private static final String ACTIVITY_JOIN = """
            LEFT JOIN festival_activity_changes a ON a.activity_id = t.activity_id
            """;
    private static final String ACTIVE_ACTIVITY = "(a.activity_state IS NULL OR a.activity_state = 'PROGRAMADA')";
    private static final String SELECT = """
            SELECT t.*, s.name AS station_name, s.capacity,
                   """ + STOCK + """
                   AS available_bikes,
                   """ + TOTAL + """
                   AS total_bikes, a.activity_state, a.modified_epoch_ms, a.reason
            FROM festival_restock_tasks t JOIN stations s ON s.id = t.station_id
            """ + ACTIVITY_JOIN;

    public record Activity(String id, String correlationId, String title, Long startMillis,
                           long deadlineMillis, int coverageMeters) {}

    public void createIfAbsent(Activity activity, long stationId, int distanceMeters, int target, Long coveredAt) {
        jdbc.update("""
                INSERT INTO festival_restock_tasks
                    (activity_id, correlation_id, activity_title, deadline_epoch_ms, station_id, distance_meters,
                     target_bikes, resolution, resolved_epoch_ms, coverage_meters, start_epoch_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, activity.id(), activity.correlationId(), activity.title(), activity.deadlineMillis(), stationId, distanceMeters,
                target, coveredAt == null ? null : "STOCK_SUFFICIENT", coveredAt, activity.coverageMeters(), activity.startMillis());
    }

    public void saveActivityChange(String activityId, String state, long modifiedAt, String reason) {
        jdbc.update("""
                INSERT INTO festival_activity_changes (activity_id, activity_state, modified_epoch_ms, reason)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    activity_state = IF(VALUES(modified_epoch_ms) > modified_epoch_ms, VALUES(activity_state), activity_state),
                    reason = IF(VALUES(modified_epoch_ms) > modified_epoch_ms, VALUES(reason), reason),
                    modified_epoch_ms = GREATEST(modified_epoch_ms, VALUES(modified_epoch_ms))
                """, activityId, state, modifiedAt, reason);
    }

    public record QaActivity(String correlationId, String title, Long start, long deadline,
                             Long previousDeadline, Long deadlineChangedAt, int coverageMeters) {}

    public Optional<QaActivity> lockQaActivity(String id) {
        return jdbc.query("""
                SELECT t.* FROM festival_restock_tasks t
                """ + ACTIVITY_JOIN + """
                WHERE t.activity_id = ? AND t.activity_title LIKE 'QA - %'
                  AND (a.activity_state IS NULL OR a.activity_state <> 'CANCELADA')
                ORDER BY t.id LIMIT 1 FOR UPDATE
                """, (rs, row) -> new QaActivity(rs.getString("correlation_id"), rs.getString("activity_title"),
                rs.getObject("start_epoch_ms", Long.class), rs.getLong("deadline_epoch_ms"),
                rs.getObject("previous_deadline_epoch_ms", Long.class),
                rs.getObject("deadline_changed_epoch_ms", Long.class), rs.getInt("coverage_meters")), id)
                .stream().findFirst();
    }

    public void changeQaDeadline(String id, long deadline, long now) {
        jdbc.update("""
                UPDATE festival_restock_tasks
                SET previous_deadline_epoch_ms = deadline_epoch_ms, deadline_epoch_ms = ?, deadline_changed_epoch_ms = ?
                WHERE activity_id = ?
                """, deadline, now, id);
        if (deadline <= now) {
            jdbc.update("""
                    UPDATE festival_restock_tasks
                    SET resolution = 'EXPIRED', resolved_epoch_ms = ?
                    WHERE activity_id = ? AND resolution IS NULL AND removed_from_coverage_epoch_ms IS NULL
                    """, deadline, id);
        }
    }

    public void markOldCoverage(String id, long now) {
        jdbc.update("""
                UPDATE festival_restock_tasks
                SET location_changed_epoch_ms = ?,
                    removed_from_coverage_epoch_ms = COALESCE(removed_from_coverage_epoch_ms, ?)
                WHERE activity_id = ?
                """, now, now, id);
    }

    public void includeInCoverage(String id, long stationId, int distance, QaActivity activity, long now) {
        jdbc.update("""
                UPDATE festival_restock_tasks
                SET distance_meters = ?, previous_deadline_epoch_ms = ?,
                    deadline_changed_epoch_ms = ?, location_changed_epoch_ms = ?, removed_from_coverage_epoch_ms = NULL
                WHERE activity_id = ? AND station_id = ?
                """, distance, activity.previousDeadline(), activity.deadlineChangedAt(), now, id, stationId);
    }

    public void reconcile(long now) {
        jdbc.update("""
                UPDATE festival_restock_tasks t JOIN stations s ON s.id = t.station_id
                """ + ACTIVITY_JOIN + """
                SET t.resolution = 'TARGET_REACHED', t.resolved_epoch_ms = ?
                WHERE t.resolution IS NULL AND t.removed_from_coverage_epoch_ms IS NULL AND t.deadline_epoch_ms > ?
                  AND s.status = 'ACTIVE' AND s.deleted_at IS NULL AND
                """ + ACTIVE_ACTIVITY + " AND " + STOCK + " >= t.target_bikes", now, now);
        jdbc.update("""
                UPDATE festival_restock_tasks SET resolution = 'EXPIRED', resolved_epoch_ms = deadline_epoch_ms
                WHERE resolution IS NULL AND removed_from_coverage_epoch_ms IS NULL AND deadline_epoch_ms <= ?
                """, now);
    }

    public List<FestivalRestockTaskResponse> findAll(long now) {
        return jdbc.query(SELECT + ORDER_BY_TASK, (rs, row) -> mapTask(rs, now));
    }

    public List<FestivalRestockTaskResponse> findPending(long now) {
        return jdbc.query(SELECT + """
                 WHERE t.resolution IS NULL AND t.removed_from_coverage_epoch_ms IS NULL
                   AND t.deadline_epoch_ms > ? AND
                """ + ACTIVE_ACTIVITY + ORDER_BY_TASK, (rs, row) -> mapTask(rs, now), now);
    }

    public Optional<FestivalRestockTaskResponse> find(long id, long now) {
        return jdbc.query(SELECT + " WHERE t.id = ?", (rs, row) -> mapTask(rs, now), id).stream().findFirst();
    }

    private static FestivalRestockTaskResponse mapTask(ResultSet rs, long now) throws SQLException {
        long deadline = rs.getLong("deadline_epoch_ms");
        String reason = rs.getString("resolution");
        Long resolved = rs.getObject("resolved_epoch_ms", Long.class);
        Long removed = rs.getObject("removed_from_coverage_epoch_ms", Long.class);
        if (reason == null && removed != null) {
            reason = OUTSIDE_COVERAGE;
            resolved = removed;
        } else if (reason == null && deadline <= now) {
            reason = EXPIRED;
            resolved = deadline;
        }
        String status = taskStatus(reason);
        int capacity = rs.getInt("capacity");
        int totalBikes = rs.getInt("total_bikes");
        String activityState = rs.getString("activity_state");
        var change = activityState == null ? null : new ActivityChange(activityState,
                Instant.ofEpochMilli(rs.getLong("modified_epoch_ms")), rs.getString("reason"));
        Long previousDeadline = rs.getObject("previous_deadline_epoch_ms", Long.class);
        Long deadlineChanged = rs.getObject("deadline_changed_epoch_ms", Long.class);
        Long locationChanged = rs.getObject("location_changed_epoch_ms", Long.class);
        return new FestivalRestockTaskResponse(
                rs.getLong("id"), rs.getString("activity_id"), rs.getString("correlation_id"),
                rs.getString("activity_title"), rs.getLong("station_id"), rs.getString("station_name"),
                rs.getInt("distance_meters"), rs.getInt("coverage_meters"), Instant.ofEpochMilli(deadline),
                capacity, totalBikes, rs.getInt("available_bikes"), Math.max(0, capacity - totalBikes),
                rs.getInt("target_bikes"), status, reason, resolved == null ? null : Instant.ofEpochMilli(resolved), change,
                previousDeadline == null ? null : Instant.ofEpochMilli(previousDeadline),
                deadlineChanged == null ? null : Instant.ofEpochMilli(deadlineChanged),
                locationChanged == null ? null : Instant.ofEpochMilli(locationChanged), removed != null);
    }

    private static String taskStatus(String reason) {
        if (reason == null) return "PENDING";
        return (EXPIRED.equals(reason) || OUTSIDE_COVERAGE.equals(reason)) ? EXPIRED : "COMPLETED";
    }

    public int updateTarget(long id, int target, long now) {
        return jdbc.update("""
                UPDATE festival_restock_tasks t JOIN stations s ON s.id = t.station_id
                """ + ACTIVITY_JOIN + """
                SET t.target_bikes = ?
                WHERE t.id = ? AND t.resolution IS NULL AND t.removed_from_coverage_epoch_ms IS NULL AND t.deadline_epoch_ms > ?
                  AND s.deleted_at IS NULL AND s.status = 'ACTIVE' AND s.capacity >= ? AND
                """ + ACTIVE_ACTIVITY, target, id, now, target);
    }

    public int completeManually(long id, long now) {
        return jdbc.update("""
                UPDATE festival_restock_tasks t
                """ + ACTIVITY_JOIN + """
                SET t.resolution = 'MANUAL', t.resolved_epoch_ms = ?
                WHERE t.id = ? AND t.resolution IS NULL AND t.removed_from_coverage_epoch_ms IS NULL AND t.deadline_epoch_ms > ? AND
                """ + ACTIVE_ACTIVITY, now, id, now);
    }

    public Map<Long, Integer> highestUpcomingTargets(long now) {
        return jdbc.query("""
                SELECT t.station_id, MAX(t.target_bikes) AS target_bikes
                FROM festival_restock_tasks t
                """ + ACTIVITY_JOIN + """
                WHERE t.deadline_epoch_ms > ? AND t.removed_from_coverage_epoch_ms IS NULL AND
                """ + ACTIVE_ACTIVITY + """
                 GROUP BY t.station_id
                """, rows -> {
            Map<Long, Integer> targets = new HashMap<>();
            while (rows.next()) {
                targets.put(rows.getLong("station_id"), rows.getInt("target_bikes"));
            }
            return targets;
        }, now);
    }
}
