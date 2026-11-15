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
                config.reservaCanceladaHandler(service), config.eventoFinalizadoHandler(service),
                config.cuInt01Handler7(service), config.cuInt01Handler8(service));
        assertThat(handlers).extracting(EventHandler::topic).containsExactly(
                "com.citypass.espacios.EventoComunitarioPublicado", "com.citypass.espacios.EventoCancelado",
                "com.citypass.espacios.EspacioEstadoCambiado", "com.citypass.espacios.ReservaConfirmada",
                "com.citypass.espacios.ReservaCancelada", "com.citypass.espacios.EventoFinalizado",
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
            context.registerBean(com.citypass.movilidad.service.ClimateAlertService.class,
                    () -> mock(com.citypass.movilidad.service.ClimateAlertService.class));
            context.registerBean(com.citypass.movilidad.service.CicloviaObstructionService.class,
                    () -> mock(com.citypass.movilidad.service.CicloviaObstructionService.class));
            context.registerBean(ObjectMapper.class);
            context.registerBean(com.citypass.movilidad.service.EmergencyService.class,
                    () -> mock(com.citypass.movilidad.service.EmergencyService.class));
            context.registerBean(com.citypass.movilidad.config.EmergencyProperties.class,
                    () -> new com.citypass.movilidad.config.EmergencyProperties(java.time.Duration.ofSeconds(10),
                            java.time.Duration.ofSeconds(60), "com.citypass.emergencias.EmergenciaActualizada"));
            context.scan("com.citypass.movilidad.eventbus.consume");
            context.refresh();

            var handlers = List.copyOf(context.getBeansOfType(EventHandler.class).values());
            // ReclamoCreado.v3 se procesa una sola vez. Un tópico sí puede tener handlers de dominios distintos:
            // EmergenciaActualizada lo atienden CU-INT-01 y el cierre de viajes por emergencia.
            assertThat(handlers).extracting(EventHandler::topic)
                    .filteredOn("com.citypass.reclamos.ReclamoCreado.v3"::equals).hasSize(1);
            assertThat(handlers).extracting(EventHandler::topic)
                    .filteredOn("com.citypass.emergencias.EmergenciaActualizada"::equals).hasSize(2);
            assertThat(handlers).extracting(EventHandler::topic)
                    .contains("com.citypass.reclamos.ReclamoCreado.v3",
                            "com.citypass.reclamos.ReclamoActualizado",
                            "com.citypass.espacios.CriticidadClimaticaConfirmada",
                            "com.citypass.espacios.CriticidadClimaticaFinalizada")
                    .doesNotContain("com.citypass.reclamos.ReclamoCreado");
            var dispatcher = new EventDispatcher(handlers, new EventEnvelopeMapper(new ObjectMapper()),
                    mock(com.citypass.movilidad.repository.EventInboxRepository.class), java.time.Clock.systemUTC());
            assertThat(dispatcher.topics()).contains("com.citypass.reclamos.ReclamoCreado.v3");
        }
    }
}
