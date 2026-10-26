package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.service.InboundEventService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Suscripciones CU-INT-01: EventDispatcher ya controla transacciones e idempotencia. */
@Configuration
public class CuInt01Handlers {

    private EventHandler handler(String topic, InboundEventService service) {
        return new EventHandler() {
            @Override public String topic() { return topic; }
            @Override public void handle(IncomingEvent event) { service.handle(topic, event.data()); }
        };
    }

    @Bean
    public EventHandler cuInt01Handler1(InboundEventService service) {
        return handler("com.citypass.espacios.EventoComunitarioPublicado", service);
    }

    @Bean
    public EventHandler cuInt01Handler2(InboundEventService service) {
        return handler("com.citypass.espacios.EventoCancelado", service);
    }

    @Bean
    public EventHandler cuInt01Handler3(InboundEventService service) {
        return handler("com.citypass.espacios.EspacioEstadoCambiado", service);
    }

    @Bean
    public EventHandler cuInt01Handler4(InboundEventService service) {
        return handler("com.citypass.espacios.ReservaCreada", service);
    }

    @Bean
    public EventHandler cuInt01Handler5(InboundEventService service) {
        return handler("com.citypass.reclamos.ReclamoCreado", service);
    }

    @Bean
    public EventHandler cuInt01Handler6(InboundEventService service) {
        return handler("com.citypass.reclamos.ReclamoActualizado", service);
    }

    @Bean
    public EventHandler cuInt01Handler7(InboundEventService service) {
        return handler("com.citypass.emergencias.EmergenciaCreada", service);
    }

    @Bean
    public EventHandler cuInt01Handler8(InboundEventService service) {
        return handler("com.citypass.emergencias.EmergenciaActualizada", service);
    }
}
