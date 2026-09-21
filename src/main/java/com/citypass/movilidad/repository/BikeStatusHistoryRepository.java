package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.BikeStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BikeStatusHistoryRepository extends JpaRepository<BikeStatusHistory, Long> {

    List<BikeStatusHistory> findAllByBikeIdOrderByChangedAtDesc(Long bikeId);
}
