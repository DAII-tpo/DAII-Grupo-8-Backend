package com.citypass.movilidad.service;

import com.citypass.movilidad.config.RecommendationProperties;
import com.citypass.movilidad.dto.NearbyStationResponse;
import com.citypass.movilidad.model.CulturalDestination;
import com.citypass.movilidad.repository.CulturalDestinationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DestinationRecommendationPublisherTest {

    private final CulturalDestinationRepository destinations = mock(CulturalDestinationRepository.class);
    private final StationRecommendationService recommendations = mock(StationRecommendationService.class);
    private final NearbyStationService nearby = mock(NearbyStationService.class);
    private final IncidentProximityFilter filter = mock(IncidentProximityFilter.class);
    private final DestinationRecommendationEventWriter writer = mock(DestinationRecommendationEventWriter.class);
    private final DestinationRecommendationPublisher publisher = new DestinationRecommendationPublisher(
            destinations, recommendations, nearby,
            new RecommendationProperties("http://localhost:8000", 1000, 2000, 1000, 15, true),
            filter, writer);

    private CulturalDestination destination() {
        return CulturalDestination.builder().eventId("ev-42").spaceId("esp-1").title("Evento")
                .latitude(BigDecimal.valueOf(-34.60)).longitude(BigDecimal.valueOf(-58.40))
                .startsAt(Instant.now().plusSeconds(3600))
                .endsAt(Instant.now().plusSeconds(7200))
                .maxCapacity(100).build();
    }

    @BeforeEach
    void setUp() {
        when(destinations.findById("ev-42")).thenReturn(Optional.of(destination()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePayload() {
        ArgumentCaptor<Map<String,Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(writer).publish(eq("ev-42"), payload.capture());
        return payload.getValue();
    }

    @Test
    void eventoCanceladoEmiteUltimaInvalidacionSinConsultarEstaciones() {
        CulturalDestination canceled = destination();
        canceled.setCancelled(true);
        when(destinations.findById("ev-42")).thenReturn(Optional.of(canceled));

        publisher.publish("ev-42");

        Map<String,Object> data = capturePayload();
        assertThat(data.get("estado")).isEqualTo("NO_RECOMMENDATION");
        assertThat(data.get("mensaje")).isEqualTo("El evento fue cancelado");
        assertThat(data.get("estacionId")).isNull();
        verifyNoInteractions(nearby, recommendations);
    }

    @Test
    void espacioDeshabilitadoInvalidaRecomendacion() {
        CulturalDestination disabled = destination();
        disabled.setSpaceEnabled(false);
        when(destinations.findById("ev-42")).thenReturn(Optional.of(disabled));

        publisher.publish("ev-42");

        assertThat(capturePayload().get("mensaje")).isEqualTo("El espacio no está disponible");
        verifyNoInteractions(nearby, recommendations);
    }

    @Test
    void todasLasEstacionesAfectadasPublicaNoRecommendationYCuentaDescartes() {
        NearbyStationResponse station = new NearbyStationResponse(1L, "A", "Direccion",
                BigDecimal.valueOf(-34.60), BigDecimal.valueOf(-58.40), 10, 15, 5, 10);
        when(nearby.findNearby(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(station));
        when(filter.filter(anyList())).thenReturn(new IncidentProximityFilter.FilterResult(List.of(), 1));

        publisher.publish("ev-42");

        Map<String,Object> data = capturePayload();
        assertThat(data.get("estado")).isEqualTo("NO_RECOMMENDATION");
        assertThat(data.get("descartadasPorIncidentes")).isEqualTo(1);
        assertThat(data.get("estacionId")).isNull();
        verifyNoInteractions(recommendations);
    }

    @Test
    void schedulerIncluyeEventoEnCursoPeroNoUnoDelMesSiguiente() {
        CulturalDestination ongoing = destination();
        ongoing.setStartsAt(Instant.now().minusSeconds(1800));
        CulturalDestination future = destination();
        future.setEventId("future");
        future.setStartsAt(Instant.now().plusSeconds(48 * 3600));
        when(destinations.findByEndsAtAfterOrderByStartsAtAsc(any()))
                .thenReturn(List.of(ongoing, future));
        when(nearby.findNearby(anyDouble(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(filter.filter(anyList()))
                .thenReturn(new IncidentProximityFilter.FilterResult(List.of(), 0));

        publisher.recalculateUpcoming();

        verify(writer).publish(eq("ev-42"), anyMap());
        verify(writer, never()).publish(eq("future"), anyMap());
    }
}
