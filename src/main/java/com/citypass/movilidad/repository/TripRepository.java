package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.enums.TripStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {

    /**
     * Viaje de un usuario en un estado dado. Con {@code TripStatus.ACTIVE} es la única consulta de
     * "viaje activo" del módulo: la usan tanto el inicio de viaje (MOV-026) como la consulta (MOV-027).
     */
    @EntityGraph(attributePaths = {"bike", "originStation"})
    Optional<Trip> findByUserIdAndStatus(Long userId, TripStatus status);

    /** Bloquea el viaje para que dos solicitudes concurrentes no puedan finalizarlo dos veces. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id = :id")
    Optional<Trip> findByIdForUpdate(@Param("id") Long id);
}
