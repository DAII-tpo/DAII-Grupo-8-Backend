package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.enums.BikeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BikeRepository extends JpaRepository<Bike, Long> {

    Optional<Bike> findByCode(String code);

    boolean existsByCode(String code);

    Optional<Bike> findByIdAndDeletedAtIsNull(Long id);

    List<Bike> findAllByStationIdAndDeletedAtIsNullOrderByCode(Long stationId);

    List<Bike> findAllByStatusAndDeletedAtIsNullOrderByCode(BikeStatus status);

    List<Bike> findAllByStationIdAndStatusAndDeletedAtIsNullOrderByCode(Long stationId, BikeStatus status);

    long countByStationIdAndDeletedAtIsNull(Long stationId);
}
