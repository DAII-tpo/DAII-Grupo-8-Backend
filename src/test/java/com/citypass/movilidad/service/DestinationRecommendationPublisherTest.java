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
            filter, writer, mock(com.citypass.movilidad.repository.DestinationRecommendationJobRepository.class));

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
        verify(writer).publish(eq("ev-42"), payload.capture(), nullable(String.class));
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

        verify(writer).publish(eq("ev-42"), anyMap(), nullable(String.class));
        verify(writer, never()).publish(eq("future"), anyMap(), nullable(String.class));
    }
    @Test
    void eventoAusenteNoPublica() {
        publisher.publish("missing");
        verifyNoInteractions(writer, nearby, recommendations);
    }

    @Test
    void sinUbicacionInvalidaEnLugarDeConsultarEstaciones() {
        var event = destination();
        event.setLatitude(null);
        when(destinations.findById("ev-42")).thenReturn(Optional.of(event));
        publisher.publish("ev-42");
        assertThat(capturePayload().get("mensaje")).isEqualTo("El evento no tiene una ubicación disponible");
        verifyNoInteractions(nearby, recommendations);
    }

    @Test
    void sinCandidatasPublicaExplicacionYContratoCompleto() {
        when(nearby.findNearby(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of());
        when(filter.filter(anyList())).thenReturn(new IncidentProximityFilter.FilterResult(List.of(), 0));
        publisher.afterDestinationChanged(new CulturalDestinationChanged("ev-42"));
        var payload = capturePayload();
        assertThat(payload).hasSize(12).containsEntry("eventoId", "ev-42")
                .containsEntry("estado", "NO_RECOMMENDATION").containsEntry("descartadasPorIncidentes", 0)
                .containsEntry("correlationId", "evento-ev-42");
        assertThat(Instant.parse((String) payload.get("calculadaEn"))).isBeforeOrEqualTo(Instant.now());
        verifyNoInteractions(recommendations);
    }

    @Test
    void recomendacionConEstacionPublicaCoordenadasYCantidadDeDescartes() {
        var station = new NearbyStationResponse(1L, "Parque", "Direccion",
                BigDecimal.valueOf(-34.6), BigDecimal.valueOf(-58.4), 100, 20, 5, 15);
        when(nearby.findNearby(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(station));
        when(filter.filter(anyList())).thenReturn(new IncidentProximityFilter.FilterResult(List.of(station), 2));
        var recommended = new com.citypass.movilidad.dto.StationRecommendationResponse.RecommendedStation(
                1L, "Parque", "Direccion", station.latitude(), station.longitude(), 100, 20, 5, 15, null);
        var response = new com.citypass.movilidad.dto.StationRecommendationResponse(
                com.citypass.movilidad.model.enums.RecommendationStatus.RECOMMENDED,
                com.citypass.movilidad.model.enums.RecommendationSource.FALLBACK,
                com.citypass.movilidad.model.enums.RecommendationPurpose.DROPOFF,
                null, "Estacion segura", recommended, List.of(), null, null, Instant.now());
        when(recommendations.recommend(-34.6, -58.4, com.citypass.movilidad.model.enums.RecommendationPurpose.DROPOFF))
                .thenReturn(response);
        publisher.publish("ev-42");
        assertThat(capturePayload()).containsEntry("estado", "RECOMMENDED").containsEntry("estacionId", 1L)
                .containsEntry("latitud", -34.6).containsEntry("longitud", -58.4)
                .containsEntry("lugaresLibres", 15).containsEntry("distanciaMetros", 100)
                .containsEntry("descartadasPorIncidentes", 2);
    }

    @Test
    void unFalloNoImpidePublicarLaSiguienteRecomendacion() {
        var first = destination();
        first.setEventId("failed");
        when(destinations.findByEndsAtAfterOrderByStartsAtAsc(any())).thenReturn(List.of(first, destination()));
        when(destinations.findById("failed")).thenThrow(new IllegalStateException("database"));
        when(nearby.findNearby(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of());
        when(filter.filter(anyList())).thenReturn(new IncidentProximityFilter.FilterResult(List.of(), 0));
        publisher.recalculateUpcoming();
        verify(writer).publish(eq("ev-42"), anyMap(), nullable(String.class));
    }

    @Test
    void incidenteUsaRadioConfiguradoParaRecalcularSinOmitirCandidatas() {
        // ~1445 m del destino: fuera de 1000+300, dentro de 1000+600.
        when(filter.radiusMeters()).thenReturn(600);
        when(destinations.findByEndsAtAfterOrderByStartsAtAsc(any())).thenReturn(List.of(destination()));
        when(nearby.findNearby(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of());
        when(filter.filter(anyList())).thenReturn(new IncidentProximityFilter.FilterResult(List.of(), 0));
        publisher.afterIncidentChanged(new TrafficIncidentChanged(-34.613, -58.4));
        verify(writer).publish(eq("ev-42"), anyMap(), nullable(String.class));
        clearInvocations(writer);
        publisher.afterIncidentChanged(new TrafficIncidentChanged(-35, -59));
        verifyNoInteractions(writer);
    }

    @Test
    void schedulerNoRepiteInvalidacionDeEventoCancelado() {
        var canceled = destination();
        canceled.setCancelled(true);
        when(destinations.findByEndsAtAfterOrderByStartsAtAsc(any())).thenReturn(List.of(canceled));
        publisher.recalculateUpcoming();
        verifyNoInteractions(writer, recommendations, nearby);
    }

    @Test
    void eventoFinalizadoInvalidaSinRecomendarUnaEstacion() {
        var event = destination();
        event.setEndsAt(Instant.now().minusSeconds(1));
        when(destinations.findById("ev-42")).thenReturn(Optional.of(event));
        publisher.publish("ev-42");
        assertThat(capturePayload()).containsEntry("estado", "NO_RECOMMENDATION")
                .containsEntry("mensaje", "El evento ya no está vigente");
        verifyNoInteractions(nearby, recommendations);
    }

}
