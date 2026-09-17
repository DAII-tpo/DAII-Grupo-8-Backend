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

    /**
     * Viaje de un usuario en un estado dado. Con {@code TripStatus.ACTIVE} es la única consulta de
     * "viaje activo" del módulo: la usan tanto el inicio de viaje (MOV-026) como la consulta (MOV-027).
     */
    @EntityGraph(attributePaths = {"bike", "originStation"})
    Optional<Trip> findByUserIdAndStatus(Long userId, TripStatus status);

    /**
     * Historial paginado de los viajes de un usuario en un estado dado, del más reciente al más
     * antiguo (MOV-030).
     *
     * El desempate por {@code id} no es decorativo: {@code started_at} es DATETIME sin fracción de
     * segundo, así que dos viajes iniciados en el mismo segundo no tendrían un orden estable y
     * podrían repetirse o perderse entre páginas.
     *
     * Las tres asociaciones del grafo son {@code @ManyToOne}, por lo que Hibernate pagina en SQL y
     * no en memoria; sin el grafo, cada viaje de la página dispararía sus propias consultas (N+1).
     */
    @EntityGraph(attributePaths = {"bike", "originStation", "destinationStation"})
    Page<Trip> findByUserIdAndStatusOrderByStartedAtDescIdDesc(Long userId, TripStatus status,
                                                               Pageable pageable);

    /** Bloquea el viaje para que dos solicitudes concurrentes no puedan finalizarlo dos veces. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id = :id")
    Optional<Trip> findByIdForUpdate(@Param("id") Long id);
}
