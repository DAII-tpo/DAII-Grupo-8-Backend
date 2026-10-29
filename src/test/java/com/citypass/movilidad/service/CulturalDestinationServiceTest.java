package com.citypass.movilidad.service;

import com.citypass.movilidad.model.CulturalDestination;
import com.citypass.movilidad.repository.CulturalDestinationRepository;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CulturalDestinationServiceTest {
    private final CulturalDestinationRepository repo = mock(CulturalDestinationRepository.class);
    private final StationRecommendationService recommendation = mock(StationRecommendationService.class);
    private final CulturalDestinationService service = new CulturalDestinationService(repo, recommendation);

    private CulturalDestination event(String id, boolean canceled, boolean enabled) {
        return CulturalDestination.builder()
                .eventId(id).spaceId("sp-1").title("Encuentro " + id)
                .startsAt(Instant.now().plusSeconds(3600))
                .endsAt(Instant.now().plusSeconds(7200))
                .maxCapacity(100).cancelled(canceled).spaceEnabled(enabled)
                .latitude(java.math.BigDecimal.valueOf(-34.6))
                .longitude(java.math.BigDecimal.valueOf(-58.4))
                .build();
    }

    @Test
    void muestraSoloEventosVigentesConEspacioHabilitado() {
        when(repo.findByEndsAtAfterOrderByStartsAtAsc(any()))
                .thenReturn(List.of(event("a", false, true),
                        event("b", true, true), event("c", false, false)));
        assertThat(service.upcoming()).extracting("eventId").containsExactly("a");
    }
}
