package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.EventInbox;
import com.citypass.movilidad.model.enums.EventInboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventInboxRepository extends JpaRepository<EventInbox, String> {

    boolean existsByEventIdAndStatus(String eventId, EventInboxStatus status);
}
