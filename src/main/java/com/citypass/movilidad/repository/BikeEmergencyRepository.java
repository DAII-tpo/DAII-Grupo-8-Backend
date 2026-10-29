package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.BikeEmergency;
import com.citypass.movilidad.model.enums.EmergencyStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BikeEmergencyRepository extends JpaRepository<BikeEmergency, Long> {

    /**
     * Emergencia con lock pesimista: cancelar, responder el cuestionario y el vencimiento del plazo
     * pueden llegar a la vez, y solo uno tiene que ganar.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from BikeEmergency e where e.id = :id")
    Optional<BikeEmergency> findByIdForUpdate(@Param("id") Long id);

    /** Última emergencia del usuario en alguno de los estados, con lo necesario para la respuesta. */
    @EntityGraph(attributePaths = {"trip", "bike"})
    Optional<BikeEmergency> findFirstByUserIdAndStatusInOrderByReportedAtDescIdDesc(
            Long userId, Collection<EmergencyStatus> statuses);

    /** Ids de las emergencias cuyo plazo para el cuestionario venció sin respuesta. */
    @Query("select e.id from BikeEmergency e where e.status = :status and e.triageDeadline <= :now")
    List<Long> findIdsByStatusAndTriageDeadlineBefore(@Param("status") EmergencyStatus status,
                                                      @Param("now") Instant now);
}
