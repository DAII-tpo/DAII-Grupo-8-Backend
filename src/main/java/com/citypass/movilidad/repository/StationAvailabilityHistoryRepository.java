package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.StationAvailabilityHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StationAvailabilityHistoryRepository extends JpaRepository<StationAvailabilityHistory, Long> {
}
