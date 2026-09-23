package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.MaintenanceRecord;
import com.citypass.movilidad.model.enums.MaintenanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface MaintenanceRecordRepository extends JpaRepository<MaintenanceRecord, Long> {
    List<MaintenanceRecord> findAllByOrderByStartedAtDesc();

    boolean existsByBikeIdAndStatus(Long bikeId, MaintenanceStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select record from MaintenanceRecord record where record.id = :id")
    Optional<MaintenanceRecord> findByIdForUpdate(@Param("id") Long id);
}
