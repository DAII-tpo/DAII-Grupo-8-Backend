package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.event.EmergenciaSolicitadaData;
import com.citypass.movilidad.dto.event.MovilidadObstruccionEvaluadaData;
import com.citypass.movilidad.dto.event.ReclamoActualizadoData;
import com.citypass.movilidad.dto.event.ReclamoCreadoData;
import com.citypass.movilidad.dto.response.CicloviaObstructionResponse;
import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.model.CicloviaObstruction;
import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.model.enums.ObstructionImpactLevel;
import com.citypass.movilidad.model.enums.ObstructionStatus;
import com.citypass.movilidad.repository.CicloviaObstructionRepository;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.projection.NearbyStationProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CicloviaObstructionServiceTest {

    private CicloviaObstructionRepository obstructionRepository;
    private StationRepository stationRepository;
    private EventPublisher eventPublisher;
    private Clock clock;
    private CicloviaObstructionService service;

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

    @BeforeEach
    void setUp() {
        obstructionRepository = mock(CicloviaObstructionRepository.class);
        stationRepository = mock(StationRepository.class);
        eventPublisher = mock(EventPublisher.class);
        clock = Clock.fixed(NOW, ZoneId.of("UTC"));

        service = new CicloviaObstructionService(obstructionRepository, stationRepository, eventPublisher, clock);
    }

    @Test
    @DisplayName("Procesa ReclamoCreado con prioridad ALTA: registra obstrucción, publica evaluación y solicita emergencia")
    void procesarReclamoCreado_prioridadAlta_publicaEvaluacionYEmergencia() {
        ReclamoCreadoData reclamo = new ReclamoCreadoData(
                "rec-123",
                "U000042",
                "APP",
                "Auto estacionado sobre la ciclovia",
                "Hay un auto arriba de la bicisenda en plena avenida",
                "TRANSITO",
                "ALTA",
                "CIUDADANO",
                null,
                "Av. Cordoba 4800",
                "Palermo",
                -34.5955,
                -58.4281,
                true,
                "req-corr-1",
                NOW.toEpochMilli()
        );

        when(obstructionRepository.findByReclamoId("rec-123")).thenReturn(Optional.empty());

        NearbyStationProjection stationProj = mock(NearbyStationProjection.class);
        when(stationProj.getId()).thenReturn(10L);
        when(stationRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of(stationProj));

        when(obstructionRepository.save(any(CicloviaObstruction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<CicloviaObstruction> resultado = service.procesarReclamoCreado(reclamo);

        assertThat(resultado).isPresent();
        CicloviaObstruction obs = resultado.get();
        assertThat(obs.getReclamoId()).isEqualTo("rec-123");
        assertThat(obs.getImpactLevel()).isEqualTo(ObstructionImpactLevel.ALTO);
        assertThat(obs.getStatus()).isEqualTo(ObstructionStatus.ACTIVE);
        assertThat(obs.isEmergencyDispatched()).isTrue();
        assertThat(obs.getAffectedStationIds()).contains("10");

        // Verifica publicación de MovilidadObstruccionEvaluada
        ArgumentCaptor<MovilidadObstruccionEvaluadaData> captorEvaluada = ArgumentCaptor.forClass(MovilidadObstruccionEvaluadaData.class);
        verify(eventPublisher).publish(eq("MovilidadObstruccionEvaluada"), eq(AggregateType.INCIDENT), eq("rec-123"), captorEvaluada.capture());
        MovilidadObstruccionEvaluadaData evaluada = captorEvaluada.getValue();
        assertThat(evaluada.reclamoId()).isEqualTo("rec-123");
        assertThat(evaluada.estacionesAfectadasIds()).containsExactly(10L);
        assertThat(evaluada.requiereIntervencionInmediata()).isTrue();

        // Verifica publicación de EmergenciaSolicitada
        ArgumentCaptor<EmergenciaSolicitadaData> captorEmergencia = ArgumentCaptor.forClass(EmergenciaSolicitadaData.class);
        verify(eventPublisher).publish(eq("EmergenciaSolicitada"), eq(AggregateType.INCIDENT), eq("rec-123"), captorEmergencia.capture());
        EmergenciaSolicitadaData emergencia = captorEmergencia.getValue();
        assertThat(emergencia.reclamoId()).isEqualTo("rec-123");
        assertThat(emergencia.prioridad()).isEqualTo("ALTA");
    }

    @Test
    @DisplayName("Procesa ReclamoCreado con prioridad MEDIA: no solicita emergencia pero sí evalúa obstrucción")
    void procesarReclamoCreado_prioridadMedia_noPublicaEmergencia() {
        ReclamoCreadoData reclamo = new ReclamoCreadoData(
                "rec-456",
                "U000042",
                "APP",
                "Bicicletero con ramas caídas en ciclovía",
                "Hay ramas sobre la ciclovía",
                "ARBOLADO",
                "MEDIA",
                "CIUDADANO",
                null,
                "Calle Falsa 123",
                "Almagro",
                -34.6000,
                -58.4200,
                true,
                "req-corr-2",
                NOW.toEpochMilli()
        );

        when(obstructionRepository.findByReclamoId("rec-456")).thenReturn(Optional.empty());
        when(stationRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(obstructionRepository.save(any(CicloviaObstruction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<CicloviaObstruction> resultado = service.procesarReclamoCreado(reclamo);

        assertThat(resultado).isPresent();
        assertThat(resultado.get().isEmergencyDispatched()).isFalse();

        verify(eventPublisher).publish(eq("MovilidadObstruccionEvaluada"), eq(AggregateType.INCIDENT), eq("rec-456"), any());
        verify(eventPublisher, never()).publish(eq("EmergenciaSolicitada"), any(), any(), any());
    }

    @Test
    @DisplayName("Reclamo sin afectación de circulación ni mención de ciclovía se omite")
    void procesarReclamoCreado_noRelevante_seOmite() {
        ReclamoCreadoData reclamo = new ReclamoCreadoData(
                "rec-999",
                "U000042",
                "APP",
                "Luminaria rota en plaza",
                "No anda la luz del farol",
                "ALUMBRADO",
                "BAJA",
                "CIUDADANO",
                null,
                "Plaza Armenia",
                "Palermo",
                -34.5800,
                -58.4100,
                false,
                "req-corr-9",
                NOW.toEpochMilli()
        );

        Optional<CicloviaObstruction> resultado = service.procesarReclamoCreado(reclamo);

        assertThat(resultado).isEmpty();
        verify(obstructionRepository, never()).save(any());
        verify(eventPublisher, never()).publish(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("ReclamoActualizado con RESUELTO normaliza y resuelve la obstrucción")
    void procesarReclamoActualizado_resuelto_cierraObstruccion() {
        CicloviaObstruction existing = CicloviaObstruction.builder()
                .id(1L)
                .reclamoId("rec-123")
                .status(ObstructionStatus.ACTIVE)
                .latitude(BigDecimal.valueOf(-34.5955))
                .longitude(BigDecimal.valueOf(-58.4281))
                .build();

        when(obstructionRepository.findByReclamoId("rec-123")).thenReturn(Optional.of(existing));
        when(obstructionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReclamoActualizadoData data = new ReclamoActualizadoData(
                "rec-123",
                "U000042",
                "EN_PROCESO",
                "RESUELTO",
                "Se retiró el vehículo",
                "cuadrilla-3",
                "operador-1",
                "req-corr-1",
                NOW.toEpochMilli()
        );

        Optional<CicloviaObstruction> resultado = service.procesarReclamoActualizado(data);

        assertThat(resultado).isPresent();
        assertThat(resultado.get().getStatus()).isEqualTo(ObstructionStatus.RESOLVED);
        assertThat(resultado.get().getResolvedAt()).isEqualTo(NOW);
        verify(obstructionRepository).save(existing);
    }

    @Test
    @DisplayName("isStationAffected devuelve true si la estación está en una obstrucción activa")
    void isStationAffected_devuelveTrueSiEstaAfectada() {
        CicloviaObstruction activeObs = CicloviaObstruction.builder()
                .reclamoId("rec-1")
                .status(ObstructionStatus.ACTIVE)
                .affectedStationIds("15,22,35")
                .build();

        when(obstructionRepository.findByStatus(ObstructionStatus.ACTIVE)).thenReturn(List.of(activeObs));

        assertThat(service.isStationAffected(22L)).isTrue();
        assertThat(service.isStationAffected(99L)).isFalse();
    }
}
