package com.citypass.movilidad.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;

/** Proyección de confirmaciones: nunca se suma a los inscriptos del snapshot cultural. */
@Repository
public class SpaceReservationRepository {
    private final JdbcTemplate jdbc;
    public SpaceReservationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void save(Reservation r) {
        jdbc.update("""
                INSERT INTO space_reservations
                (reservation_id, reservation_type, resource_id, space_id, people, starts_at, ends_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE reservation_type=VALUES(reservation_type), resource_id=VALUES(resource_id),
                space_id=VALUES(space_id), people=VALUES(people), starts_at=VALUES(starts_at), ends_at=VALUES(ends_at)
                """, r.id(), r.type(), r.resourceId(), r.spaceId(), r.people(),
                Timestamp.from(r.startsAt()), Timestamp.from(r.endsAt()));
    }

    public record Reservation(String id, String type, String resourceId, String spaceId,
                              int people, Instant startsAt, Instant endsAt) { }
}
