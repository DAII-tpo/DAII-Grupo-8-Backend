package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.repository.projection.StationBikeCountProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BikeRepository extends JpaRepository<Bike, Long> {

    Optional<Bike> findByCode(String code);

    /**
     * Cuenta, por estación, las bicicletas presentes y cuántas de ellas están disponibles.
     * Las bicicletas IN_USE tienen station_id en NULL, así que quedan fuera del conteo por
     * construcción: no ocupan anclaje mientras dura el viaje.
     * Una estación sin bicicletas no genera fila; el service la interpreta como cero.
     */
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
}
