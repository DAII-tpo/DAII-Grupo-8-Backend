package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.event.EmergenciaSolicitadaData;
import com.citypass.movilidad.dto.event.MovilidadObstruccionEvaluadaData;
import com.citypass.movilidad.dto.event.ReclamoActualizadoData;
import com.citypass.movilidad.dto.event.ReclamoCreadoData;
import com.citypass.movilidad.dto.event.ReclamoReclasificadoData;
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
import java.util.Set;

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
                "RECIBIDO",
                "CIUDADANO",
                null,
                "Av. Cordoba 4800",
                "Palermo",
                -34.5955,
                -58.4281,
                "CICLOVIA",
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
        assertThat(evaluada.requiereGrua()).isTrue();

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
                "RECIBIDO",
                "CIUDADANO",
                null,
                "Calle Falsa 123",
                "Almagro",
                -34.6000,
                -58.4200,
                "CICLOVIA",
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

        ArgumentCaptor<MovilidadObstruccionEvaluadaData> captor = ArgumentCaptor.forClass(MovilidadObstruccionEvaluadaData.class);
        verify(eventPublisher).publish(eq("MovilidadObstruccionEvaluada"), eq(AggregateType.INCIDENT), eq("rec-456"), captor.capture());
        verify(eventPublisher, never()).publish(eq("EmergenciaSolicitada"), any(), any(), any());
        assertThat(captor.getValue().requiereGrua()).isFalse();
    }

    @Test
    @DisplayName("No solicita emergencia mientras el reclamo está en revisión humana")
    void procesarReclamoCreado_enRevision_noDisparaEmergencia() {
        ReclamoCreadoData reclamo = new ReclamoCreadoData(
                "rec-review", "U000042", "APP", "Auto sobre ciclovía", "Bloquea la ciclovía",
                "TRANSITO", "CRITICA", "EN_REVISION", "MODELO", 0.42,
                "Av. Córdoba 100", "Palermo", -34.5955, -58.4281, "CICLOVIA", true,
                "corr-review", NOW.toEpochMilli()
        );
        when(obstructionRepository.findByReclamoId("rec-review")).thenReturn(Optional.empty());
        when(stationRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(obstructionRepository.save(any(CicloviaObstruction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<CicloviaObstruction> resultado = service.procesarReclamoCreado(reclamo);

        assertThat(resultado).isPresent();
        assertThat(resultado.get().getImpactLevel()).isEqualTo(ObstructionImpactLevel.BAJO);
        assertThat(resultado.get().isEmergencyDispatched()).isFalse();
        verify(eventPublisher, never()).publish(eq("EmergenciaSolicitada"), any(), any(), any());
    }

    @Test
    @DisplayName("Reclasificación actualiza la evaluación y solicita emergencia con prioridad crítica vigente")
    void procesarReclamoReclasificado_prioridadCritica_actualizaYEscala() {
        CicloviaObstruction existing = CicloviaObstruction.builder()
                .reclamoId("rec-reclassified")
                .status(ObstructionStatus.ACTIVE)
                .reclamoEstado("RECIBIDO")
                .latitude(BigDecimal.valueOf(-34.5955))
                .longitude(BigDecimal.valueOf(-58.4281))
                .emergencyDispatched(false)
                .build();
        when(obstructionRepository.findByReclamoId("rec-reclassified")).thenReturn(Optional.of(existing));
        when(obstructionRepository.save(any(CicloviaObstruction.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stationRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of());

        ReclamoReclasificadoData data = new ReclamoReclasificadoData(
                "rec-reclassified", "U000042", "BACHES", "TRANSITO", "MEDIA", "CRITICA",
                "CICLOVIA", "CICLOVIA", true, true, "Auto sobre ciclovía", "Bloquea el paso",
                "Palermo", -34.5955, -58.4281, "OPERADOR", "corr-reclassified", NOW.toEpochMilli()
        );

        Optional<CicloviaObstruction> resultado = service.procesarReclamoReclasificado(data);

        assertThat(resultado).isPresent();
        assertThat(resultado.get().isEmergencyDispatched()).isTrue();
        assertThat(resultado.get().getReclamoPrioridad()).isEqualTo("CRITICA");
        ArgumentCaptor<MovilidadObstruccionEvaluadaData> evaluadaCaptor = ArgumentCaptor.forClass(MovilidadObstruccionEvaluadaData.class);
        verify(eventPublisher).publish(eq("MovilidadObstruccionEvaluada"), eq(AggregateType.INCIDENT),
                eq("rec-reclassified"), evaluadaCaptor.capture());
        assertThat(evaluadaCaptor.getValue().requiereGrua()).isTrue();
        verify(eventPublisher).publish(eq("EmergenciaSolicitada"), eq(AggregateType.INCIDENT),
                eq("rec-reclassified"), any(EmergenciaSolicitadaData.class));
    }

    @Test
    @DisplayName("Al pasar de EN_REVISION a RECIBIDO se evalúa la prioridad ya reclasificada")
    void procesarReclamoActualizado_recibidoTrasRevision_solicitaEmergenciaSiCorresponde() {
        CicloviaObstruction existing = CicloviaObstruction.builder()
                .reclamoId("rec-reviewed")
                .status(ObstructionStatus.ACTIVE)
                .reclamoEstado("EN_REVISION")
                .reclamoPrioridad("ALTA")
                .reclamoCategoria("TRANSITO")
                .objetoAfectado("CICLOVIA")
                .afectaCirculacion(true)
                .latitude(BigDecimal.valueOf(-34.5955))
                .longitude(BigDecimal.valueOf(-58.4281))
                .impactLevel(ObstructionImpactLevel.ALTO)
                .emergencyDispatched(false)
                .build();
        when(obstructionRepository.findByReclamoId("rec-reviewed")).thenReturn(Optional.of(existing));
        when(obstructionRepository.save(any(CicloviaObstruction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.procesarReclamoActualizado(new ReclamoActualizadoData(
                "rec-reviewed", "U000042", "EN_REVISION", "RECIBIDO", "Revisión humana completada",
                "operador-1", "operador-1", "corr-reviewed", NOW.toEpochMilli()
        ));

        assertThat(existing.isEmergencyDispatched()).isTrue();
        assertThat(existing.getReclamoEstado()).isEqualTo("RECIBIDO");
        verify(eventPublisher).publish(eq("EmergenciaSolicitada"), eq(AggregateType.INCIDENT),
                eq("rec-reviewed"), any(EmergenciaSolicitadaData.class));
    }

    @Test
    @DisplayName("Reclamo que menciona ciclovía pero no la identifica como objeto afectado se omite")
    void procesarReclamoCreado_noRelevante_seOmite() {
        ReclamoCreadoData reclamo = new ReclamoCreadoData(
                "rec-999",
                "U000042",
                "APP",
                "Auto estacionado sobre la ciclovía",
                "El vehículo bloquea el carril bici",
                "TRANSITO",
                "BAJA",
                "RECIBIDO",
                "CIUDADANO",
                null,
                "Plaza Armenia",
                "Palermo",
                -34.5800,
                -58.4100,
                "CALLE",
                true,
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

    @Test
    void reclamoCreadoRechazaNulosDuplicadosYCoordenadasInvalidas() {
        assertThat(service.procesarReclamoCreado(null)).isEmpty();
        assertThat(service.procesarReclamoCreado(reclamoCreadoSinId())).isEmpty();

        CicloviaObstruction duplicate = CicloviaObstruction.builder().reclamoId("duplicado").build();
        when(obstructionRepository.findByReclamoId("duplicado")).thenReturn(Optional.of(duplicate));
        assertThat(service.procesarReclamoCreado(reclamo("duplicado", -34.6, -58.4))).contains(duplicate);

        when(obstructionRepository.findByReclamoId("sin-coordenadas")).thenReturn(Optional.empty());
        assertThat(service.procesarReclamoCreado(reclamo("sin-coordenadas", null, -58.4))).isEmpty();
        verify(stationRepository, never()).findNearby(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyInt(), anyString(), anyInt());
        verify(eventPublisher, never()).publish(anyString(), any(), any(), any());
    }

    @Test
    void reclamoActualizadoIgnoraNuloSinIdYReclamosNoEncontrados() {
        assertThat(service.procesarReclamoActualizado(null)).isEmpty();
        assertThat(service.procesarReclamoActualizado(new ReclamoActualizadoData(
                null, "u", "EN_REVISION", "RECIBIDO", null, null, null, null, NOW.toEpochMilli()))).isEmpty();
        when(obstructionRepository.findByReclamoId("missing")).thenReturn(Optional.empty());
        assertThat(service.procesarReclamoActualizado(new ReclamoActualizadoData(
                "missing", "u", "EN_REVISION", "RECIBIDO", null, null, null, null, NOW.toEpochMilli()))).isEmpty();
        verify(obstructionRepository, never()).save(any());
    }

    @Test
    void reclasificacionNulaNoActivaOSinCoordenadasSeIgnora() {
        assertThat(service.procesarReclamoReclasificado(null)).isEmpty();
        assertThat(service.procesarReclamoReclasificado(reclasificado(null, "CICLOVIA", true))).isEmpty();

        CicloviaObstruction resolved = CicloviaObstruction.builder().reclamoId("cerrado")
                .status(ObstructionStatus.RESOLVED).latitude(BigDecimal.valueOf(-34.6))
                .longitude(BigDecimal.valueOf(-58.4)).build();
        when(obstructionRepository.findByReclamoId("cerrado")).thenReturn(Optional.of(resolved));
        assertThat(service.procesarReclamoReclasificado(reclasificado("cerrado", "CICLOVIA", true)))
                .contains(resolved);

        CicloviaObstruction invalid = CicloviaObstruction.builder().reclamoId("invalid")
                .status(ObstructionStatus.ACTIVE).latitude(BigDecimal.valueOf(-34.6))
                .longitude(BigDecimal.valueOf(-58.4)).build();
        when(obstructionRepository.findByReclamoId("invalid")).thenReturn(Optional.of(invalid));
        assertThat(service.procesarReclamoReclasificado(reclasificado("invalid", "CICLOVIA", true,
                91.0, -58.4))).contains(invalid);
        verify(eventPublisher, never()).publish(anyString(), any(), any(), any());
    }

    @Test
    void reclasificacionQueDejaDeAfectarCicloviaCierraYPublicaEvaluacion() {
        CicloviaObstruction active = CicloviaObstruction.builder().reclamoId("fuera-de-alcance")
                .status(ObstructionStatus.ACTIVE).barrio("Palermo")
                .latitude(BigDecimal.valueOf(-34.6)).longitude(BigDecimal.valueOf(-58.4))
                .affectedStationIds("1,2").detourDescription("desvío").build();
        when(obstructionRepository.findByReclamoId("fuera-de-alcance")).thenReturn(Optional.of(active));
        when(obstructionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.procesarReclamoReclasificado(reclasificado(
                "fuera-de-alcance", "CALLE", false));

        assertThat(result).contains(active);
        assertThat(active.getStatus()).isEqualTo(ObstructionStatus.RESOLVED);
        assertThat(active.getResolvedAt()).isEqualTo(NOW);
        assertThat(active.getBarrio()).isEqualTo("Palermo");
        assertThat(active.getAffectedStationIds()).isEmpty();
        assertThat(active.getImpactLevel()).isEqualTo(ObstructionImpactLevel.BAJO);
        assertThat(active.getDetourDescription()).isNull();
        ArgumentCaptor<MovilidadObstruccionEvaluadaData> captor =
                ArgumentCaptor.forClass(MovilidadObstruccionEvaluadaData.class);
        verify(eventPublisher).publish(eq("MovilidadObstruccionEvaluada"), eq(AggregateType.INCIDENT),
                eq("fuera-de-alcance"), captor.capture());
        assertThat(captor.getValue().afectaCiclovia()).isFalse();
        assertThat(captor.getValue().estacionesAfectadasIds()).isEmpty();
    }

    @Test
    void reclasificacionUsaUbicacionExistenteYNoDuplicaEmergenciaYaDespachada() {
        CicloviaObstruction active = CicloviaObstruction.builder().reclamoId("ya-despachada")
                .status(ObstructionStatus.ACTIVE).reclamoEstado("RECIBIDO").reclamoPrioridad("ALTA")
                .reclamoCategoria("TRANSITO").objetoAfectado("CICLOVIA").afectaCirculacion(true)
                .emergencyDispatched(true).latitude(BigDecimal.valueOf(-34.6))
                .longitude(BigDecimal.valueOf(-58.4)).build();
        when(obstructionRepository.findByReclamoId("ya-despachada")).thenReturn(Optional.of(active));
        when(obstructionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(stationRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of());

        service.procesarReclamoReclasificado(reclasificado("ya-despachada", "CICLOVIA", true,
                null, null));

        assertThat(active.getLatitude()).isEqualByComparingTo("-34.6");
        assertThat(active.getLongitude()).isEqualByComparingTo("-58.4");
        assertThat(active.isEmergencyDispatched()).isTrue();
        verify(eventPublisher, never()).publish(eq("EmergenciaSolicitada"), any(), any(), any());
    }

    @Test
    void consultasDeObstruccionesMapeanRespuestasYEstacionesAfectadas() {
        CicloviaObstruction active = CicloviaObstruction.builder().id(7L).reclamoId("active")
                .status(ObstructionStatus.ACTIVE).affectedStationIds(" 12, , 24 ")
                .impactLevel(ObstructionImpactLevel.MEDIO).latitude(BigDecimal.valueOf(-34.6))
                .longitude(BigDecimal.valueOf(-58.4)).emergencyDispatched(false).build();
        when(obstructionRepository.findByStatus(ObstructionStatus.ACTIVE)).thenReturn(List.of(active));

        var response = service.obtenerObstruccionesActivas();

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().reclamoId()).isEqualTo("active");
        assertThat(response.getFirst().affectedStationIds()).containsExactly(12L, 24L);
        assertThat(service.isStationAffected(null)).isFalse();
        assertThat(service.affectedStationIds(null)).isEmpty();
        assertThat(service.affectedStationIds(List.of())).isEmpty();
        assertThat(service.affectedStationIds(List.of(24L, 99L))).isEqualTo(Set.of(24L));
    }

    private ReclamoCreadoData reclamoCreadoSinId() {
        return reclamo(null, -34.6, -58.4);
    }

    private ReclamoCreadoData reclamo(String id, Double lat, Double lon) {
        return new ReclamoCreadoData(id, "u-1", "APP", "Auto en ciclovía", "Bloquea el paso",
                "TRANSITO", "MEDIA", "RECIBIDO", "CIUDADANO", null, null, "Palermo",
                lat, lon, "CICLOVIA", true, "corr-1", NOW.toEpochMilli());
    }

    private ReclamoReclasificadoData reclasificado(String id, String objeto, boolean afecta) {
        return reclasificado(id, objeto, afecta, -34.6, -58.4);
    }

    private ReclamoReclasificadoData reclasificado(String id, String objeto, boolean afecta,
                                                   Double lat, Double lon) {
        return new ReclamoReclasificadoData(id, "u-1", "TRANSITO", "TRANSITO", "MEDIA", "MEDIA",
                "CICLOVIA", objeto, true, afecta, "Auto en ciclovía", "Bloquea el paso", null,
                lat, lon, "operador", "corr-1", NOW.toEpochMilli());
    }
}

