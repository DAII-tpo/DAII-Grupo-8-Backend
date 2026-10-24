package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.CulturalDestination;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface CulturalDestinationRepository extends JpaRepository<CulturalDestination, String> {

    List<CulturalDestination> findBySpaceId(String spaceId);

    /** Eventos que todavía no terminaron, del más próximo al más lejano. */
    List<CulturalDestination> findByEndsAtAfterOrderByStartsAtAsc(Instant now);
}
