package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.EventMobilityDecisionRequest;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.CulturalDestination;
import com.citypass.movilidad.dto.StationRecommendationResponse;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import com.citypass.movilidad.repository.CulturalDestinationRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class EventMobilityDecisionServiceTest {
    @Test
    void recomiendaOrigenConBicicletasYDestinoConAnclajesCuandoElCiudadanoAsiste() {
        var destinations = mock(CulturalDestinationRepository.class);
        var event = CulturalDestination.builder()
                .eventId("evento-1").spaceId("plaza-1").title("Feria barrial")
                .latitude(BigDecimal.valueOf(-34.6037)).longitude(BigDecimal.valueOf(-58.3816))
                .startsAt(Instant.parse("2030-11-10T10:00:00Z"))
                .endsAt(Instant.parse("2030-11-10T20:00:00Z"))
                .maxCapacity(200).registrations(80).eventStatus("ACTIVE").spaceEnabled(true).build();
        when(destinations.findById("evento-1")).thenReturn(Optional.of(event));
        var recommendations = mock(StationRecommendationService.class);
        var pickup = mock(StationRecommendationResponse.class);
        var dropoff = mock(StationRecommendationResponse.class);
        when(recommendations.recommend(-34.61, -58.39, RecommendationPurpose.PICKUP)).thenReturn(pickup);
        when(recommendations.recommendDestination(-34.6037, -58.3816, 80)).thenReturn(dropoff);
        var service = new EventMobilityDecisionService(destinations, recommendations);

        var response = service.decide("evento-1", new EventMobilityDecisionRequest(true, -34.61, -58.39));

        assertThat(response.mode()).isEqualTo("ATTENDING");
        assertThat(response.pickup()).isSameAs(pickup);
        assertThat(response.dropoff()).isSameAs(dropoff);
        assertThat(response.alternativeDropoff()).isNull();
        verify(recommendations).recommendDestination(-34.6037, -58.3816, 80);
    }

    @Test
    void recomiendaUnaEstacionRealSinCrearReservasCuandoElCiudadanoNoAsiste() {
        var destinations = mock(CulturalDestinationRepository.class);
        var event = CulturalDestination.builder()
                .eventId("evento-1").spaceId("plaza-1").title("Feria barrial")
                .latitude(BigDecimal.valueOf(-34.6037)).longitude(BigDecimal.valueOf(-58.3816))
                .startsAt(Instant.parse("2030-11-10T10:00:00Z"))
                .endsAt(Instant.parse("2030-11-10T20:00:00Z"))
                .maxCapacity(200).eventStatus("ACTIVE").spaceEnabled(true).build();
        when(destinations.findById("evento-1")).thenReturn(Optional.of(event));
        var recommendations = mock(StationRecommendationService.class);
        var alternative = mock(StationRecommendationResponse.class);
        when(recommendations.recommendAlternativeDropoff(-34.6037, -58.3816, 0)).thenReturn(alternative);
        var service = new EventMobilityDecisionService(destinations, recommendations);

        var response = service.decide("evento-1", new EventMobilityDecisionRequest(false, -34.61, -58.39));

        assertThat(response.mode()).isEqualTo("AVOIDING_EVENT");
        assertThat(response.alternativeDropoff()).isSameAs(alternative);
        assertThat(response.pickup()).isNull();
        verify(recommendations).recommendAlternativeDropoff(-34.6037, -58.3816, 0);
    }

    @Test
    void usaDemandaProyectadaParaElDestinoDelEvento() {
        var destinations = mock(CulturalDestinationRepository.class);
        var event = CulturalDestination.builder().eventId("evento-1").spaceId("plaza-1").title("Feria")
                .latitude(BigDecimal.valueOf(-34.6037)).longitude(BigDecimal.valueOf(-58.3816))
                .startsAt(Instant.parse("2030-11-10T10:00:00Z")).endsAt(Instant.parse("2030-11-10T20:00:00Z"))
                .maxCapacity(200).registrations(80).eventStatus("ACTIVE").spaceEnabled(true).build();
        when(destinations.findById("evento-1")).thenReturn(Optional.of(event));
        var recommendations = mock(StationRecommendationService.class);
        var intentions = mock(EventArrivalIntentService.class);
        var projected = mock(EventRecommendationResult.class);
        var dropoff = mock(StationRecommendationResponse.class);
        when(intentions.projectedArrivals(eq("evento-1"), any(), any())).thenReturn(Map.of(77L, 20));
        when(recommendations.recommendEventDestination(-34.6037, -58.3816, Map.of(77L, 20), false)).thenReturn(projected);
        when(projected.recommendation()).thenReturn(dropoff);
        when(projected.projections()).thenReturn(List.of());

        var response = new EventMobilityDecisionService(destinations, recommendations, intentions)
                .decide("evento-1", new EventMobilityDecisionRequest(true, -34.61, -58.39));

        assertThat(response.dropoff()).isSameAs(dropoff);
        verify(recommendations, never()).recommendDestination(anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void usaFallbackSiFallaLaConsultaDeDemanda() {
        var destinations = mock(CulturalDestinationRepository.class);
        var event = CulturalDestination.builder().eventId("evento-1").spaceId("plaza-1").title("Feria")
                .latitude(BigDecimal.valueOf(-34.6037)).longitude(BigDecimal.valueOf(-58.3816))
                .startsAt(Instant.parse("2030-11-10T10:00:00Z")).endsAt(Instant.parse("2030-11-10T20:00:00Z"))
                .maxCapacity(200).eventStatus("ACTIVE").spaceEnabled(true).build();
        when(destinations.findById("evento-1")).thenReturn(Optional.of(event));
        var recommendations = mock(StationRecommendationService.class);
        var intentions = mock(EventArrivalIntentService.class);
        var dropoff = mock(StationRecommendationResponse.class);
        when(intentions.projectedArrivals(any(), any(), any())).thenThrow(new RuntimeException("db"));
        when(recommendations.recommendDestination(-34.6037, -58.3816, 0)).thenReturn(dropoff);

        var response = new EventMobilityDecisionService(destinations, recommendations, intentions)
                .decide("evento-1", new EventMobilityDecisionRequest(true, -34.61, -58.39));

        assertThat(response.dropoff()).isSameAs(dropoff);
    }

    @Test
    void rechazaOrigenAusenteYEventoFinalizado() {
        var destinations = mock(CulturalDestinationRepository.class);
        var event = CulturalDestination.builder().eventId("evento-1").spaceId("plaza-1").title("Feria")
                .latitude(BigDecimal.valueOf(-34.6037)).longitude(BigDecimal.valueOf(-58.3816))
                .startsAt(Instant.parse("2020-11-10T10:00:00Z")).endsAt(Instant.parse("2020-11-10T20:00:00Z"))
                .maxCapacity(200).eventStatus("ACTIVE").spaceEnabled(true).build();
        when(destinations.findById("evento-1")).thenReturn(Optional.of(event));
        var service = new EventMobilityDecisionService(destinations, mock(StationRecommendationService.class));
        assertThatThrownBy(() -> service.decide("evento-1", new EventMobilityDecisionRequest(true, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        event.setEndsAt(Instant.parse("2030-11-10T20:00:00Z"));
        assertThatThrownBy(() -> service.decide("evento-1", new EventMobilityDecisionRequest(true, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usaDemandaProyectadaTambienParaAlternativasPreventivas() {
        var destinations = mock(CulturalDestinationRepository.class);
        var event = CulturalDestination.builder().eventId("evento-1").spaceId("plaza-1").title("Feria")
                .latitude(BigDecimal.valueOf(-34.6037)).longitude(BigDecimal.valueOf(-58.3816))
                .startsAt(Instant.parse("2030-11-10T10:00:00Z")).endsAt(Instant.parse("2030-11-10T20:00:00Z"))
                .maxCapacity(200).eventStatus("ACTIVE").spaceEnabled(true).build();
        when(destinations.findById("evento-1")).thenReturn(Optional.of(event));
        var recommendations = mock(StationRecommendationService.class);
        var intentions = mock(EventArrivalIntentService.class);
        var projected = mock(EventRecommendationResult.class);
        when(intentions.projectedArrivals(any(), any(), any())).thenReturn(Map.of(77L, 4));
        when(recommendations.recommendEventDestination(-34.6037, -58.3816, Map.of(77L, 4), true)).thenReturn(projected);
        when(projected.recommendation()).thenReturn(null);
        when(projected.projections()).thenReturn(List.of());

        var response = new EventMobilityDecisionService(destinations, recommendations, intentions)
                .decide("evento-1", new EventMobilityDecisionRequest(false, null, null));

        assertThat(response.mode()).isEqualTo("AVOIDING_EVENT");
        assertThat(response.alternativeDropoff()).isNull();
    }
}
