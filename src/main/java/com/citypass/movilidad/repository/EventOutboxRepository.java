package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.EventOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventOutboxRepository extends JpaRepository<EventOutbox, String> {
}
