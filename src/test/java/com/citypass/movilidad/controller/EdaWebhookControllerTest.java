package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.event.EdaWebhookEnvelope;
import com.citypass.movilidad.dto.event.ReclamoActualizadoData;
import com.citypass.movilidad.dto.event.ReclamoCreadoData;
import com.citypass.movilidad.eventbus.EventMetadata;
import com.citypass.movilidad.model.EventInbox;
import com.citypass.movilidad.model.enums.EventInboxStatus;
import com.citypass.movilidad.repository.EventInboxRepository;
import com.citypass.movilidad.service.CicloviaObstructionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EdaWebhookControllerTest {

    private CicloviaObstructionService obstructionService;
    private EventInboxRepository inboxRepository;
    private ObjectMapper objectMapper;
    private Clock clock;
    private EdaWebhookController controller;

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

    @BeforeEach
    void setUp() {
        obstructionService = mock(CicloviaObstructionService.class);
        inboxRepository = mock(EventInboxRepository.class);
        objectMapper = new ObjectMapper();
        clock = Clock.fixed(NOW, ZoneId.of("UTC"));

        controller = new EdaWebhookController(obstructionService, inboxRepository, objectMapper, clock);
    }

    @Test
    @DisplayName("Webhook procesa ReclamoCreado y registra en inbox")
    void receiveEvent_reclamoCreado_procesaYRegistraInbox() throws Exception {
        String dataJson = """
                {
                  "reclamoId": "rec-1",
                  "titulo": "Auto en bicisenda",
                  "afectaCirculacion": true,
                  "latitud": -34.5955,
                  "longitud": -58.4281
                }
                """;
        EventMetadata metadata = new EventMetadata("evt-100", "com.citypass.reclamos.ReclamoCreado.v3",
                NOW, "grupo5", "tok-1", 1, "hash", "1.0", "gw-1");
        EdaWebhookEnvelope envelope = new EdaWebhookEnvelope(metadata, objectMapper.readTree(dataJson));

        when(inboxRepository.existsByEventIdAndStatus("evt-100", EventInboxStatus.PROCESSED)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.receiveEvent(envelope);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("status", "PROCESSED");
        verify(obstructionService).procesarReclamoCreado(any(ReclamoCreadoData.class));
        verify(inboxRepository).save(any(EventInbox.class));
    }

    @Test
    @DisplayName("Webhook descarta duplicados si ya están PROCESSED en inbox")
    void receiveEvent_duplicado_retornaAlreadyProcessed() throws Exception {
        EventMetadata metadata = new EventMetadata("evt-100", "com.citypass.reclamos.ReclamoCreado.v3",
                NOW, "grupo5", "tok-1", 1, "hash", "1.0", "gw-1");
        EdaWebhookEnvelope envelope = new EdaWebhookEnvelope(metadata, objectMapper.createObjectNode());

        when(inboxRepository.existsByEventIdAndStatus("evt-100", EventInboxStatus.PROCESSED)).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.receiveEvent(envelope);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("status", "ALREADY_PROCESSED");
        verify(obstructionService, never()).procesarReclamoCreado(any());
    }

    @Test
    @DisplayName("Webhook procesa ReclamoActualizado con estado RESUELTO")
    void receiveEvent_reclamoActualizado_procesa() throws Exception {
        String dataJson = """
                {
                  "reclamoId": "rec-1",
                  "estadoNuevo": "RESUELTO"
                }
                """;
        EventMetadata metadata = new EventMetadata("evt-101", "com.citypass.reclamos.ReclamoActualizado",
                NOW, "grupo5", "tok-1", 1, "hash", "1.0", "gw-1");
        EdaWebhookEnvelope envelope = new EdaWebhookEnvelope(metadata, objectMapper.readTree(dataJson));

        ResponseEntity<Map<String, Object>> response = controller.receiveEvent(envelope);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("status", "PROCESSED");
        verify(obstructionService).procesarReclamoActualizado(any(ReclamoActualizadoData.class));
    }
}

