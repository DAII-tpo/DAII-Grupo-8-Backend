package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.repository.projection.StationBikeCountProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BikeRepository extends JpaRepository<Bike, Long> {

    Optional<Bike> findByCode(String code);

    /** Por estación: bicis presentes y cuántas están disponibles. Las IN_USE no tienen estación y no cuentan. */
    @Query("""
            select b.station.id as stationId,
                   count(b) as totalBikes,
                   sum(case when b.status = :availableStatus then 1L else 0L end) as availableBikes
            from Bike b
            where b.deletedAt is null
              and b.station.id in :stationIds
            group by b.station.id
            """)
    List<StationBikeCountProjection> countBikesByStationIds(@Param("stationIds") Collection<Long> stationIds,
                                                            @Param("availableStatus") BikeStatus availableStatus);
    boolean existsByCode(String code);

    Optional<Bike> findByIdAndDeletedAtIsNull(Long id);

    /** Bici con lock pesimista, para que no se use dos veces a la vez. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Bike b where b.id = :id and b.deletedAt is null")
    Optional<Bike> findByIdAndDeletedAtIsNullForUpdate(@Param("id") Long id);

    List<Bike> findAllByStationIdAndDeletedAtIsNullOrderByCode(Long stationId);

    List<Bike> findAllByStatusAndDeletedAtIsNullOrderByCode(BikeStatus status);

    List<Bike> findAllByStationIdAndStatusAndDeletedAtIsNullOrderByCode(Long stationId, BikeStatus status);

    long countByStationIdAndDeletedAtIsNull(Long stationId);

    /** Todas las bicis vigentes con su estación en la misma consulta (panel de administración). */
    @EntityGraph(attributePaths = "station")
    @Query("select b from Bike b where b.deletedAt is null order by b.code")
    List<Bike> findAllForAdminList();

    @EntityGraph(attributePaths = "station")
    @Query("select b from Bike b where b.deletedAt is null and b.status = :status order by b.code")
    List<Bike> findAllForAdminListByStatus(@Param("status") BikeStatus status);
}
