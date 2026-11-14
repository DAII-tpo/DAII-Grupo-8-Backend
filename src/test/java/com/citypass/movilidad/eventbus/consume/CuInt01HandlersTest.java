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
                config.cuInt01Handler3(service), config.cuInt01Handler4(service),
                config.cuInt01Handler7(service), config.cuInt01Handler8(service));
        assertThat(handlers).extracting(EventHandler::topic).containsExactly(
                "com.citypass.espacios.EventoComunitarioPublicado", "com.citypass.espacios.EventoCancelado",
                "com.citypass.espacios.EspacioEstadoCambiado", "com.citypass.espacios.ReservaConfirmada",
                "com.citypass.emergencias.EmergenciaCreada",
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

    @Test void registroCompletoSuscribeV3UnaSolaVezSinElTopicoAnterior() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(InboundEventService.class, () -> mock(InboundEventService.class));
            context.registerBean(com.citypass.movilidad.service.CicloviaObstructionService.class,
                    () -> mock(com.citypass.movilidad.service.CicloviaObstructionService.class));
            context.registerBean(ObjectMapper.class);
            context.scan("com.citypass.movilidad.eventbus.consume");
            context.refresh();

            var handlers = List.copyOf(context.getBeansOfType(EventHandler.class).values());
            assertThat(handlers).extracting(EventHandler::topic).doesNotHaveDuplicates()
                    .contains("com.citypass.reclamos.ReclamoCreado.v3",
                            "com.citypass.reclamos.ReclamoActualizado")
                    .doesNotContain("com.citypass.reclamos.ReclamoCreado");
            var dispatcher = new EventDispatcher(handlers, new EventEnvelopeMapper(new ObjectMapper()),
                    mock(com.citypass.movilidad.repository.EventInboxRepository.class), java.time.Clock.systemUTC());
            assertThat(dispatcher.topics()).contains("com.citypass.reclamos.ReclamoCreado.v3");
        }
    }
}
