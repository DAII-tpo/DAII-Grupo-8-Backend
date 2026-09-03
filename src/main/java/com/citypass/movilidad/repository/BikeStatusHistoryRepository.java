package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.BikeStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BikeStatusHistoryRepository extends JpaRepository<BikeStatusHistory, Long> {
}
