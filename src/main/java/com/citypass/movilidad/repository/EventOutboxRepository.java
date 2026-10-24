package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.EventOutbox;
import com.citypass.movilidad.model.enums.EventOutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventOutboxRepository extends JpaRepository<EventOutbox, String> {

    /** Lote de pendientes en orden de creación: el gateway recibe los viajes en el orden en que pasaron. */
    List<EventOutbox> findTop50ByStatusOrderByCreatedAtAsc(EventOutboxStatus status);
}
