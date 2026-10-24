package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.InboundEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundEventRepository extends JpaRepository<InboundEvent, String> {
}
