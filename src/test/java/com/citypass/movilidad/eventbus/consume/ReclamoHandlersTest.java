package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.dto.event.ReclamoActualizadoData;
import com.citypass.movilidad.dto.event.ReclamoCreadoData;
import com.citypass.movilidad.eventbus.EventMetadata;
import com.citypass.movilidad.service.CicloviaObstructionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReclamoHandlersTest {

    private CicloviaObstructionService obstructionService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        obstructionService = mock(CicloviaObstructionService.class);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("ReclamoCreadoHandler deserializa y deriva a CicloviaObstructionService")
    void reclamoCreadoHandler_invocaServicio() throws Exception {
        ReclamoCreadoHandler handler = new ReclamoCreadoHandler(obstructionService, objectMapper);
        assertThat(handler.topic()).isEqualTo("com.citypass.reclamos.ReclamoCreado.v3");

        String json = """
                {
                  "reclamoId": "b1d4e8f2",
                  "ciudadanoId": "U000042",
                  "canal": "APP",
                  "titulo": "Auto en ciclovia",
                  "descripcion": "No deja pasar",
                  "categoria": "TRANSITO",
                  "prioridad": "ALTA",
                  "estado": "RECIBIDO",
                  "origenClasificacion": "CIUDADANO",
                  "confianzaClasificacion": null,
                  "direccion": "Av. Cordoba 4800",
                  "barrio": "Palermo",
                  "latitud": -34.5955,
                  "longitud": -58.4281,
                  "objetoAfectado": "CICLOVIA",
                  "alertaId": null,
                  "afectaCirculacion": true,
                  "correlationId": "req-1",
                  "createdAt": 1792345678901,
                  "causaId": null,
                  "causaTipo": null,
                  "actividadId": null
                }
                """;
        JsonNode dataNode = objectMapper.readTree(json);
        EventMetadata metadata = new EventMetadata("evt-1", "com.citypass.reclamos.ReclamoCreado.v3",
                Instant.now(), "grupo5", "tok-1", 1, "hash", "1.0", "gw-1");
        IncomingEvent event = new IncomingEvent("com.citypass.reclamos.ReclamoCreado.v3", metadata, dataNode);

        handler.handle(event);

        ArgumentCaptor<ReclamoCreadoData> captor = ArgumentCaptor.forClass(ReclamoCreadoData.class);
        verify(obstructionService).procesarReclamoCreado(captor.capture());
        assertThat(captor.getValue().categoria()).isEqualTo("TRANSITO");
        assertThat(captor.getValue().objetoAfectado()).isEqualTo("CICLOVIA");
        assertThat(captor.getValue().estado()).isEqualTo("RECIBIDO");
    }

    @Test
    @DisplayName("ReclamoActualizadoHandler deserializa y deriva a CicloviaObstructionService")
    void reclamoActualizadoHandler_invocaServicio() throws Exception {
        var inboundEventService = mock(com.citypass.movilidad.service.InboundEventService.class);
        ReclamoActualizadoHandler handler = new ReclamoActualizadoHandler(
                obstructionService, inboundEventService, objectMapper);
        assertThat(handler.topic()).isEqualTo("com.citypass.reclamos.ReclamoActualizado");

        String json = """
                {
                  "reclamoId": "b1d4e8f2",
                  "ciudadanoId": "U000042",
                  "estadoAnterior": "EN_PROCESO",
                  "estadoNuevo": "RESUELTO",
                  "motivo": "Se retiro vehiculo",
                  "asignadoA": "cuadrilla-3",
                  "cambiadoPor": "operador-1",
                  "correlationId": "req-1",
                  "updatedAt": 1792349999000
                }
                """;
        JsonNode dataNode = objectMapper.readTree(json);
        EventMetadata metadata = new EventMetadata("evt-2", "com.citypass.reclamos.ReclamoActualizado",
                Instant.now(), "grupo5", "tok-2", 2, "hash2", "1.0", "gw-1");
        IncomingEvent event = new IncomingEvent("com.citypass.reclamos.ReclamoActualizado", metadata, dataNode);

        handler.handle(event);

        verify(obstructionService).procesarReclamoActualizado(any(ReclamoActualizadoData.class));
        verify(inboundEventService).handle("com.citypass.reclamos.ReclamoActualizado",
                dataNode, metadata.receivedAt());
    }
}

