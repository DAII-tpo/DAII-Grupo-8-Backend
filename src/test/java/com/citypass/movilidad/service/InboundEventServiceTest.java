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
    private InboundEventService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        destinations = mock(CulturalDestinationRepository.class);
        changes = mock(ApplicationEventPublisher.class);
        service = new InboundEventService(destinations, mock(CityIncidentRepository.class), changes);
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
}
