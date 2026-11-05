package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.EventOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface EventOutboxRepository extends JpaRepository<EventOutbox, String> {

    /**
     * Próximo evento pendiente cuyo reintento ya venció, bloqueado para esta transacción: con varias
     * instancias, la segunda espera en lugar de publicarlo de nuevo. Sin SKIP LOCKED a propósito,
     * porque no todas las bases gestionadas compatibles con MySQL lo soportan.
     */
    @Query(value = """
            SELECT * FROM event_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY created_at, id
            LIMIT 1
            FOR UPDATE
            """, nativeQuery = true)
    Optional<EventOutbox> findNextPendingForUpdate(@Param("now") Instant now);
}
