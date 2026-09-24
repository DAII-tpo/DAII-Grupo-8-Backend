package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.enums.TripStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {

    /** Viaje de un usuario en un estado (con ACTIVE: su viaje activo). */
    @EntityGraph(attributePaths = {"bike", "originStation"})
    Optional<Trip> findByUserIdAndStatus(Long userId, TripStatus status);

    /**
     * Historial paginado, del más reciente al más antiguo. El desempate por id evita que viajes
     * del mismo segundo se repitan o se pierdan entre páginas.
     */
    @EntityGraph(attributePaths = {"bike", "originStation", "destinationStation"})
    Page<Trip> findByUserIdAndStatusOrderByStartedAtDescIdDesc(Long userId, TripStatus status,
                                                               Pageable pageable);

    /** Viaje con lock pesimista, para que no se finalice dos veces. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id = :id")
    Optional<Trip> findByIdForUpdate(@Param("id") Long id);
}
