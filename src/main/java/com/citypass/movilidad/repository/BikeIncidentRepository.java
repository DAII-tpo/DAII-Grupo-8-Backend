package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.BikeIncident;
import com.citypass.movilidad.model.enums.BikeIncidentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BikeIncidentRepository extends JpaRepository<BikeIncident, Long> {

    @EntityGraph(attributePaths = {"bike", "reportedByUser", "incidentType", "resolvedByUser"})
    @Query("""
            select incident from BikeIncident incident
            where (:status is null or incident.status = :status)
              and (:bikeId is null or incident.bike.id = :bikeId)
              and (:userId is null or incident.reportedByUser.id = :userId)
              and (:typeId is null or incident.incidentType.id = :typeId)
            order by incident.reportedAt desc
            """)
    List<BikeIncident> search(@Param("status") BikeIncidentStatus status,
                              @Param("bikeId") Long bikeId,
                              @Param("userId") Long userId,
                              @Param("typeId") Long typeId);

    @EntityGraph(attributePaths = {"bike", "reportedByUser", "incidentType", "resolvedByUser"})
    @Query("select incident from BikeIncident incident where incident.id = :id")
    Optional<BikeIncident> findDetailedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select incident from BikeIncident incident where incident.id = :id")
    Optional<BikeIncident> findByIdForUpdate(@Param("id") Long id);

    boolean existsByTripIdAndStatusIn(Long tripId, Collection<BikeIncidentStatus> statuses);

    boolean existsByBikeIdAndStatusInAndIdNot(Long bikeId, Collection<BikeIncidentStatus> statuses, Long id);
}
