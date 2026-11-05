package com.citypass.movilidad.eventbus.consume;
import com.citypass.movilidad.service.InboundEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
class CuInt01HandlersTest {
    @Test void suscribeSoloContratosConocidosYEnrutaElPayloadSinCambiarlo() throws Exception {
        var service = mock(InboundEventService.class);
        var config = new CuInt01Handlers();
        var handlers = List.of(config.cuInt01Handler1(service), config.cuInt01Handler2(service),
                config.cuInt01Handler3(service), config.cuInt01Handler4(service), config.cuInt01Handler5(service), config.cuInt01Handler6(service),
                config.cuInt01Handler7(service), config.cuInt01Handler8(service));
        assertThat(handlers).extracting(EventHandler::topic).containsExactly(
                "com.citypass.espacios.EventoComunitarioPublicado", "com.citypass.espacios.EventoCancelado",
                "com.citypass.espacios.EspacioEstadoCambiado", "com.citypass.espacios.ReservaConfirmada",
                "com.citypass.reclamos.ReclamoCreado",
                "com.citypass.reclamos.ReclamoActualizado", "com.citypass.emergencias.EmergenciaCreada",
                "com.citypass.emergencias.EmergenciaActualizada");
        var payload = new ObjectMapper().readTree("{\"eventoId\":\"ev-1\"}");
        var receivedAt = java.time.Instant.parse("2026-11-05T15:00:00.123456789Z");
        var metadata = new com.citypass.movilidad.eventbus.EventMetadata(
                "bus-event", "type", receivedAt, "grupo7", null, null, null, null, null);
        for (var handler : handlers) {
            handler.handle(new IncomingEvent(handler.topic(), metadata, payload));
            verify(service).handle(handler.topic(), payload, receivedAt);
        }
    }
}
