package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.IncidentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface IncidentTypeRepository extends JpaRepository<IncidentType, Long> {

    Optional<IncidentType> findByCode(String code);

    Optional<IncidentType> findByIdAndActiveTrue(Long id);

    List<IncidentType> findAllByActiveTrueOrderByName();
}
