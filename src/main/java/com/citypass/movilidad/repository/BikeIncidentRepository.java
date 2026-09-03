package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.BikeIncident;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BikeIncidentRepository extends JpaRepository<BikeIncident, Long> {
}
