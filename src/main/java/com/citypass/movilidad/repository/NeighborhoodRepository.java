package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.Neighborhood;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NeighborhoodRepository extends JpaRepository<Neighborhood, String> {
    Optional<Neighborhood> findByNormalizedName(String normalizedName);
}
