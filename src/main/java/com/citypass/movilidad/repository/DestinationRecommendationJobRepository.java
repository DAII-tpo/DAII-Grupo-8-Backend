package com.citypass.movilidad.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

/** A new token prevents an old calculation from acknowledging a newer change. */
@Repository
public class DestinationRecommendationJobRepository {
    private final JdbcTemplate jdbc;
    public DestinationRecommendationJobRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void request(String eventId) {
        jdbc.update("""
                INSERT INTO destination_recommendation_jobs (event_id, request_token) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE request_token = VALUES(request_token)
                """, eventId, UUID.randomUUID().toString());
    }

    public String token(String eventId) {
        List<String> tokens = jdbc.queryForList(
                "SELECT request_token FROM destination_recommendation_jobs WHERE event_id = ?", String.class, eventId);
        return tokens.isEmpty() ? null : tokens.getFirst();
    }

    public List<String> pendingIds() {
        return jdbc.queryForList("SELECT event_id FROM destination_recommendation_jobs ORDER BY event_id", String.class);
    }

    public void complete(String eventId, String token) {
        jdbc.update("DELETE FROM destination_recommendation_jobs WHERE event_id = ? AND request_token = ?",
                eventId, token);
    }
}
