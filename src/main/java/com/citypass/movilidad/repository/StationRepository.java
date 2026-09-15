package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.Station;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StationRepository extends JpaRepository<Station, Long> {

    Optional<Station> findByExternalId(String externalId);

    Optional<Station> findByIdAndDeletedAtIsNull(Long id);

    /** Bloquea la estación destino al devolver una bicicleta para no superar su capacidad. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Station s where s.id = :id and s.deletedAt is null")
    Optional<Station> findByIdAndDeletedAtIsNullForUpdate(@Param("id") Long id);

    List<Station> findAllByDeletedAtIsNullOrderByName();
}
