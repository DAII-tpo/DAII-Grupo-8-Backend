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

    /** Bloquea la estación destino al devolver una bicicleta para no superar su capacidad. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Station s where s.id = :id and s.deletedAt is null")
    Optional<Station> findByIdAndDeletedAtIsNullForUpdate(@Param("id") Long id);

    List<Station> findAllByDeletedAtIsNullOrderByName();

    /**
     * Estaciones activas dentro del radio, ordenadas de más cercana a más lejana (MOV-017).
     *
     * La distancia se calcula con la formula de Haversine sobre una esfera, en metros.
     *
     * Se hace con funciones matematicas comunes (ACOS/COS/SIN/RADIANS) y no con
     * ST_Distance_Sphere porque la base de produccion no implementa las funciones espaciales de
     * MySQL: la version anterior fallaba ahi con "FUNCTION movilidad.point does not exist",
     * aunque pasara en los tests, que corren contra un MySQL real.
     *
     * El 6370986 es el radio terrestre en metros que ST_Distance_Sphere usa por defecto, asi que
     * las distancias y el orden no cambian respecto de la version anterior.
     *
     * El GREATEST/LEAST acota el argumento de ACOS a [-1, 1]: en coordenadas identicas el
     * redondeo en punto flotante puede pasarse de 1 y ACOS devolveria NULL.
     *
     * El BETWEEN sobre latitud/longitud es un prefiltro por caja que permite usar el índice
     * idx_stations_lat_lng; sin él, la función sobre las columnas obligaría a recorrer la
     * tabla entera. El filtro exacto por radio se aplica después, sobre la distancia real.
     *
     * Se usa una tabla derivada en lugar de HAVING porque HAVING sin GROUP BY puede fallar
     * según el sql_mode del servidor (ONLY_FULL_GROUP_BY).
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
