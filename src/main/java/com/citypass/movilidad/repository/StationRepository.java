package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.Station;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import com.citypass.movilidad.repository.projection.NearbyStationProjection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StationRepository extends JpaRepository<Station, Long> {

    @Query("select s.externalId from Station s where s.externalId is not null")
    List<String> findAllExternalIds();

    Optional<Station> findByIdAndDeletedAtIsNull(Long id);

    /** Estación con lock pesimista, para no superar su capacidad al dejar una bici. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Station s where s.id = :id and s.deletedAt is null")
    Optional<Station> findByIdAndDeletedAtIsNullForUpdate(@Param("id") Long id);

    List<Station> findAllByDeletedAtIsNullOrderByName();

    /**
     * Estaciones activas dentro del radio, de la más cercana a la más lejana.
     * Distancia con Haversine (sin funciones espaciales: la base de producción no las soporta).
     * El BETWEEN es un prefiltro por caja que usa el índice (lat, lng).
     */
    @Query(value = """
            SELECT * FROM (
                SELECT s.id        AS id,
                       s.name      AS name,
                       s.address   AS address,
                       s.latitude  AS latitude,
                       s.longitude AS longitude,
                       s.capacity  AS capacity,
                       6370986 * ACOS(GREATEST(-1.0, LEAST(1.0,
                             COS(RADIANS(:latitude)) * COS(RADIANS(s.latitude))
                                 * COS(RADIANS(s.longitude) - RADIANS(:longitude))
                           + SIN(RADIANS(:latitude)) * SIN(RADIANS(s.latitude))
                       ))) AS distanceMeters
                FROM stations s
                WHERE s.deleted_at IS NULL
                  AND s.status = :activeStatus
                  AND s.latitude  BETWEEN :minLatitude  AND :maxLatitude
                  AND s.longitude BETWEEN :minLongitude AND :maxLongitude
            ) nearby
            WHERE nearby.distanceMeters <= :radiusMeters
            ORDER BY nearby.distanceMeters ASC
            LIMIT :maxResults
            """, nativeQuery = true)
    List<NearbyStationProjection> findNearby(@Param("latitude") double latitude,
                                             @Param("longitude") double longitude,
                                             @Param("minLatitude") double minLatitude,
                                             @Param("maxLatitude") double maxLatitude,
                                             @Param("minLongitude") double minLongitude,
                                             @Param("maxLongitude") double maxLongitude,
                                             @Param("radiusMeters") int radiusMeters,
                                             @Param("activeStatus") String activeStatus,
                                             @Param("maxResults") int maxResults);
}
