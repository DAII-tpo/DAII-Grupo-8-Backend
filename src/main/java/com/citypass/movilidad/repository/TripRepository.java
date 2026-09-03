package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.Trip;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripRepository extends JpaRepository<Trip, Long> {
}
