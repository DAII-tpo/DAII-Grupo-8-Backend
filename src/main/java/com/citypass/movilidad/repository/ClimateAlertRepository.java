package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.ClimateAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClimateAlertRepository extends JpaRepository<ClimateAlert, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ClimateAlert> findByExternalAlertId(String externalAlertId);

    List<ClimateAlert> findByStatusAndValidFromLessThanEqual(ClimateAlert.Status status, Instant now);

    /** Alertas abiertas cuya vigencia ya terminó: las cierra el job de vencimiento. */
    List<ClimateAlert> findByStatusInAndValidUntilLessThanEqual(Collection<ClimateAlert.Status> statuses, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ClimateAlert a where a.id = :id")
    Optional<ClimateAlert> findByIdForUpdate(@Param("id") Long id);

    @Query("select a from ClimateAlert a join a.neighborhoods n where a.status = :status and n.id = :neighborhoodId")
    List<ClimateAlert> findByStatusAndNeighborhoodId(@Param("status") ClimateAlert.Status status,
                                                      @Param("neighborhoodId") String neighborhoodId);

    List<ClimateAlert> findByStatusIn(List<ClimateAlert.Status> statuses);

    List<ClimateAlert> findByStatus(ClimateAlert.Status status);
}
