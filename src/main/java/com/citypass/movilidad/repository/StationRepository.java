package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.repository.projection.NearbyStationProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StationRepository extends JpaRepository<Station, Long> {

    Optional<Station> findByExternalId(String externalId);

    Optional<Station> findByIdAndDeletedAtIsNull(Long id);

    List<Station> findAllByDeletedAtIsNullOrderByName();

    /**
     * Estaciones activas dentro del radio, ordenadas de más cercana a más lejana (MOV-017).
     *
     * La distancia la resuelve MySQL con ST_Distance_Sphere, que devuelve metros sobre una
     * esfera. Ojo con el orden de los argumentos de POINT: es (longitud, latitud).
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
                       ST_Distance_Sphere(POINT(s.longitude, s.latitude), POINT(:longitude, :latitude))
                           AS distanceMeters
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
