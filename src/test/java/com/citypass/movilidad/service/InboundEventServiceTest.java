package com.citypass.movilidad.service;

import com.citypass.movilidad.model.CulturalDestination;
import com.citypass.movilidad.repository.CityIncidentRepository;
import com.citypass.movilidad.repository.CulturalDestinationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InboundEventServiceTest {

    private CulturalDestinationRepository destinations;
    private ApplicationEventPublisher changes;
    private CityIncidentRepository incidentRepo;
    private InboundEventService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        destinations = mock(CulturalDestinationRepository.class);
        changes = mock(ApplicationEventPublisher.class);
        incidentRepo = mock(CityIncidentRepository.class);
        service = new InboundEventService(destinations, incidentRepo, changes);
        when(destinations.findById("ev-42")).thenReturn(Optional.empty());
    }

    @Test
    void aceptaCapacidadCuandoFaltaCupoMaximo() throws Exception {
        service.handle("com.citypass.espacios.EventoComunitarioPublicado", mapper.readTree("""
                {
                  "eventoId":"ev-42", "espacioId":"esp-7", "titulo":"Encuentro cultural",
                  "inicio":"2026-11-01T15:00:00Z", "fin":"2026-11-01T18:00:00Z",
                  "latitud":-34.6, "longitud":-58.4, "capacidad":300, "estado":"PUBLISHED"
                }
                """));
        ArgumentCaptor<CulturalDestination> saved = ArgumentCaptor.forClass(CulturalDestination.class);
        verify(destinations).save(saved.capture());
        assertThat(saved.getValue().getMaxCapacity()).isEqualTo(300);
        verify(changes).publishEvent(eq(new CulturalDestinationChanged("ev-42")));
    }

    @Test
    void usaCupoMaximoComoCampoPreferidoCuandoVienenLosDos() throws Exception {
        service.handle("com.citypass.espacios.EventoComunitarioPublicado", mapper.readTree("""
                {
                  "eventoId":"ev-42", "espacioId":"esp-7", "titulo":"Encuentro cultural",
                  "inicio":"2026-11-01T15:00:00Z", "fin":"2026-11-01T18:00:00Z",
                  "cupoMaximo":300, "capacidad":300, "estado":"PUBLISHED"
                }
                """));
        ArgumentCaptor<CulturalDestination> saved = ArgumentCaptor.forClass(CulturalDestination.class);
        verify(destinations).save(saved.capture());
        assertThat(saved.getValue().getMaxCapacity()).isEqualTo(300);
    }

    @Test
    void rechazaLaAusenciaDeAmbosCamposEnLugarDeGuardarCero() throws Exception {
        assertThatThrownBy(() -> service.handle("com.citypass.espacios.EventoComunitarioPublicado", mapper.readTree("""
                {
                  "eventoId":"ev-42", "espacioId":"esp-7", "titulo":"Encuentro cultural",
                  "inicio":"2026-11-01T15:00:00Z", "fin":"2026-11-01T18:00:00Z"
                }
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cupoMaximo/capacidad");
        verify(destinations, never()).save(any());
    }
    @Test
    void eventoCanceladoMarcaDestinoEInvalida() throws Exception {
        CulturalDestination event = CulturalDestination.builder().eventId("ev-42")
                .spaceId("esp-7").title("Evento").maxCapacity(100).build();
        when(destinations.findById("ev-42")).thenReturn(Optional.of(event));
        service.handle("com.citypass.espacios.EventoCancelado",
                mapper.readTree("{\"eventoId\":\"ev-42\",\"estado\":\"CANCELLED\"}"));
        assertThat(event.isCancelled()).isTrue();
        verify(changes).publishEvent(eq(new CulturalDestinationChanged("ev-42")));
    }

    @Test
    void estadoDesconocidoDeEspacioNoDeshabilitaPorError() throws Exception {
        assertThatThrownBy(() -> service.handle("com.citypass.espacios.EspacioEstadoCambiado",
                mapper.readTree("{\"espacioId\":\"esp-7\",\"estadoNuevo\":\"UNKNOWN\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(destinations, never()).findBySpaceId(any());
    }

    @Test
    void reclamoSinAfectacionDeCirculacionNoSeRegistra() throws Exception {
        service.handle("com.citypass.reclamos.ReclamoCreado",
                mapper.readTree("{\"reclamoId\":\"r-1\",\"afectaCirculacion\":false,\"latitud\":-34.6,\"longitud\":-58.4}"));
        verifyNoInteractions(incidents());
    }

    private CityIncidentRepository incidents() {
        return incidentRepo;
    }

    @Test
    void emergenciaActivaSeGuardaConFechaYNotifica() throws Exception {
        service.handle("com.citypass.emergencias.EmergenciaCreada", mapper.readTree("""
                {"emergenciaId":"e-1","afectaCirculacion":true,"categoria":"INCENDIO",
                 "latitud":-34.6,"longitud":-58.4,"createdAt":"2026-10-29T15:00:00Z"}
                """));
        var saved = ArgumentCaptor.forClass(com.citypass.movilidad.model.CityIncident.class);
        verify(incidentRepo).save(saved.capture());
        assertThat(saved.getValue().isActive()).isTrue();
        assertThat(saved.getValue().getReportedAt()).isEqualTo(java.time.Instant.parse("2026-10-29T15:00:00Z"));
        verify(changes).publishEvent(new TrafficIncidentChanged(-34.6, -58.4));
    }

    @Test
    void reclamoUsaEpochMillisYResolucionEsIdempotente() throws Exception {
        var incident = com.citypass.movilidad.model.CityIncident.builder()
                .latitude(java.math.BigDecimal.valueOf(-34.6)).longitude(java.math.BigDecimal.valueOf(-58.4))
                .active(true).build();
        when(incidentRepo.findBySourceAndExternalId(com.citypass.movilidad.model.enums.IncidentSource.RECLAMOS, "r-1"))
                .thenReturn(Optional.of(incident));
        service.handle("com.citypass.reclamos.ReclamoCreado", mapper.readTree("""
                {"reclamoId":"r-1","afectaCirculacion":true,"categoria":"CALLE",
                 "latitud":-34.6,"longitud":-58.4,"createdAt":1000}
                """));
        assertThat(incident.getReportedAt()).isEqualTo(java.time.Instant.ofEpochMilli(1000));
        clearInvocations(incidentRepo, changes);
        var resolved = mapper.readTree("{\"reclamoId\":\"r-1\",\"estadoNuevo\":\"RESUELTO\"}");
        service.handle("com.citypass.reclamos.ReclamoActualizado", resolved);
        service.handle("com.citypass.reclamos.ReclamoActualizado", resolved);
        assertThat(incident.isActive()).isFalse();
        verify(incidentRepo).save(incident);
        verify(changes).publishEvent(new TrafficIncidentChanged(-34.6, -58.4));
    }

    @Test
    void emergenciaResueltaSeCierraPeroEstadoIntermedioNo() throws Exception {
        var incident = com.citypass.movilidad.model.CityIncident.builder().active(true)
                .latitude(java.math.BigDecimal.ZERO).longitude(java.math.BigDecimal.ZERO).build();
        when(incidentRepo.findBySourceAndExternalId(com.citypass.movilidad.model.enums.IncidentSource.EMERGENCIAS, "e-1"))
                .thenReturn(Optional.of(incident));
        service.handle("com.citypass.emergencias.EmergenciaActualizada",
                mapper.readTree("{\"emergenciaId\":\"e-1\",\"estadoNuevo\":\"ABIERTA\"}"));
        verifyNoInteractions(incidentRepo);
        service.handle("com.citypass.emergencias.EmergenciaActualizada",
                mapper.readTree("{\"emergenciaId\":\"e-1\",\"estadoNuevo\":\"RESUELTA\"}"));
        assertThat(incident.isActive()).isFalse();
        verify(incidentRepo).save(incident);
    }

    @Test
    void deshabilitarYHabilitarEspacioNotificaLosDestinos() throws Exception {
        var destination = CulturalDestination.builder().eventId("ev-42").build();
        when(destinations.findBySpaceId("esp-7")).thenReturn(java.util.List.of(destination));
        for (String status : java.util.List.of("DISABLED", "ENABLED")) {
            service.handle("com.citypass.espacios.EspacioEstadoCambiado",
                    mapper.readTree("{\"espacioId\":\"esp-7\",\"estadoNuevo\":\"" + status + "\"}"));
            assertThat(destination.isSpaceEnabled()).isEqualTo(status.equals("ENABLED"));
        }
        verify(destinations, times(2)).save(destination);
        verify(changes, times(2)).publishEvent(new CulturalDestinationChanged("ev-42"));
    }

    @Test
    void ignoraTiposSinContratoYRechazaIdFaltante() throws Exception {
        service.handle("com.citypass.espacios.ReservaConfirmada", mapper.readTree("{}"));
        verifyNoInteractions(destinations, incidentRepo, changes);
        assertThatThrownBy(() -> service.handle("com.citypass.reclamos.ReclamoCreado", mapper.readTree("{}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reclamoId");
    }

}
