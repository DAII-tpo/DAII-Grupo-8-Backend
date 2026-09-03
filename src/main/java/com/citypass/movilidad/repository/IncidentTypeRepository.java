package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.IncidentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IncidentTypeRepository extends JpaRepository<IncidentType, Long> {

    Optional<IncidentType> findByCode(String code);
}
