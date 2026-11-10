package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.CityIncident;
import com.citypass.movilidad.model.enums.IncidentSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CityIncidentRepository extends JpaRepository<CityIncident, Long> {

    List<CityIncident> findByActiveTrue();

    Optional<CityIncident> findBySourceAndExternalId(IncidentSource source, String externalId);
}
