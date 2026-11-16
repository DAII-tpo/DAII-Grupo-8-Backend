package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.EventArrivalIntent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EventArrivalIntentRepository extends JpaRepository<EventArrivalIntent, Long> {
    Optional<EventArrivalIntent> findByUserIdAndEventId(Long userId, String eventId);

    @Query("""
            select i.stationId as stationId, count(i) as arrivals
            from EventArrivalIntent i
            where i.eventId = :eventId and i.status = 'ACTIVE'
              and i.arrivalAt between :from and :to
            group by i.stationId
            """)
    List<ArrivalCount> countActiveByStation(@Param("eventId") String eventId,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to);

    interface ArrivalCount {
        Long getStationId();
        long getArrivals();
    }
}
