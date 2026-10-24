package com.citypass.movilidad.service;

import com.citypass.movilidad.model.CityIncident;
import com.citypass.movilidad.model.CulturalDestination;
import com.citypass.movilidad.model.enums.IncidentSource;
import com.citypass.movilidad.repository.CityIncidentRepository;
import com.citypass.movilidad.repository.CulturalDestinationRepository;
import com.citypass.movilidad.repository.InboundEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboundEventServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private InboundEventRepository inboundRepository;
    private CulturalDestinationRepository destinationRepository;
    private CityIncidentRepository incidentRepository;
    private InboundEventService service;

    @BeforeEach
    void setUp() {
        inboundRepository = mock(InboundEventRepository.class);
        destinationRepository = mock(CulturalDestinationRepository.class);
        incidentRepository = mock(CityIncidentRepository.class);
        service = new InboundEventService(inboundRepository, destinationRepository, incidentRepository);

        when(destinationRepository.findById(anyString())).thenReturn(Optional.empty());
        when(incidentRepository.findBySourceAndExternalId(any(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void registraElEventoCulturalComoDestinoConSuUbicacion() {
        service.handle(envelope("e-1", "com.citypass.espacios.EventoComunitarioPublicado", """
                {"eventoId":"ev-1","espacioId":"sp-1","titulo":"Feria del libro","categoria":"FERIA",
                 "zona":"Palermo","direccion":"Plaza Italia","latitud":-34.5811,"longitud":-58.4210,
                 "inicio":"2026-11-01T15:00:00Z","fin":"2026-11-01T20:00:00Z","cupoMaximo":300,
                 "inscriptos":12,"estado":"ACTIVE","correlationId":null}
                """));

        ArgumentCaptor<CulturalDestination> saved = ArgumentCaptor.forClass(CulturalDestination.class);
        verify(destinationRepository).save(saved.capture());
        CulturalDestination destination = saved.getValue();
        assertThat(destination.getEventId()).isEqualTo("ev-1");
        assertThat(destination.getLatitude()).isEqualByComparingTo("-34.5811");
        assertThat(destination.getRegistrations()).isEqualTo(12);
        assertThat(destination.isRoutable()).isTrue();
    }

    @Test
    void unReclamoQueAfectaLaCirculacionAbreUnIncidenteActivo() {
        service.handle(envelope("e-2", "com.citypass.reclamos.ReclamoCreado", """
                {"reclamoId":"r-9","categoria":"TRANSITO","latitud":-34.6037,"longitud":-58.3816,
                 "afectaCirculacion":true,"createdAt":1792000000000}
                """));

        ArgumentCaptor<CityIncident> saved = ArgumentCaptor.forClass(CityIncident.class);
        verify(incidentRepository).save(saved.capture());
        assertThat(saved.getValue().getSource()).isEqualTo(IncidentSource.RECLAMOS);
        assertThat(saved.getValue().isActive()).isTrue();
    }

    @Test
    void unReclamoQueNoAfectaLaCirculacionSeIgnora() {
        service.handle(envelope("e-3", "com.citypass.reclamos.ReclamoCreado", """
                {"reclamoId":"r-10","categoria":"ALUMBRADO","latitud":-34.6,"longitud":-58.3,
                 "afectaCirculacion":false,"createdAt":1792000000000}
                """));

        verify(incidentRepository, never()).save(any());
    }

    @Test
    void unaEmergenciaSinCoordenadasSeIgnora() {
        // Las emergencias médicas y de pánico llegan sin ubicación a propósito.
        service.handle(envelope("e-4", "com.citypass.emergencias.EmergenciaCreada", """
                {"emergenciaId":"em-1","categoria":"MEDICA","latitud":null,"longitud":null,
                 "afectaCirculacion":false,"createdAt":"2026-11-01T15:00:00Z"}
                """));

        verify(incidentRepository, never()).save(any());
    }

    @Test
    void laEmergenciaResueltaDesactivaElIncidente() {
        CityIncident active = CityIncident.builder().source(IncidentSource.EMERGENCIAS).externalId("em-2")
                .category("INCENDIO").latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).active(true).build();
        when(incidentRepository.findBySourceAndExternalId(IncidentSource.EMERGENCIAS, "em-2"))
                .thenReturn(Optional.of(active));

        service.handle(envelope("e-5", "com.citypass.emergencias.EmergenciaActualizada", """
                {"emergenciaId":"em-2","estadoNuevo":"RESUELTA","estadoAnterior":"EN_LUGAR"}
                """));

        assertThat(active.isActive()).isFalse();
        verify(incidentRepository).save(active);
    }

    @Test
    void elEventoCanceladoDejaDeSerDestino() {
        CulturalDestination destination = CulturalDestination.builder().eventId("ev-2").spaceId("sp-1")
                .latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).build();
        when(destinationRepository.findById("ev-2")).thenReturn(Optional.of(destination));

        service.handle(envelope("e-6", "com.citypass.espacios.EventoCancelado", """
                {"eventoId":"ev-2","espacioId":"sp-1","motivo":"Incendio cercano"}
                """));

        assertThat(destination.isRoutable()).isFalse();
    }

    @Test
    void unaReentregaDelMismoEventoNoSeProcesaDosVeces() {
        when(inboundRepository.existsById("e-7")).thenReturn(true);

        boolean processed = service.handle(envelope("e-7", "com.citypass.reclamos.ReclamoCreado", """
                {"reclamoId":"r-11","categoria":"TRANSITO","latitud":-34.6,"longitud":-58.3,
                 "afectaCirculacion":true,"createdAt":1792000000000}
                """));

        assertThat(processed).isFalse();
        verify(incidentRepository, never()).save(any());
    }

    @Test
    void unEventoSinMetadataSeRechaza() {
        assertThatThrownBy(() -> service.handle(mapper.readTree("{\"data\":{}}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private JsonNode envelope(String eventId, String eventType, String data) {
        try {
            return mapper.readTree("""
                    {"metadata":{"eventId":"%s","eventType":"%s","source":"test"},"data":%s}
                    """.formatted(eventId, eventType, data));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
