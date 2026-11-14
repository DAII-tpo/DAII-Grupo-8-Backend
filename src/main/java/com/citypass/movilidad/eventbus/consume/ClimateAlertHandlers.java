package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.service.ClimateAlertService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Suscripciones climáticas; EventDispatcher conserva la transacción y la idempotencia. */
@Configuration
public class ClimateAlertHandlers {
    @Bean
    public EventHandler climateConfirmed(ClimateAlertService alerts) {
        return new EventHandler() {
            @Override
            public String topic() {
                return "com.citypass.espacios.CriticidadClimaticaConfirmada";
            }

            @Override
            public void handle(IncomingEvent event) {
                alerts.confirm(event.data(), event.metadata().receivedAt());
            }
        };
    }

    @Bean
    public EventHandler climateFinished(ClimateAlertService alerts) {
        return new EventHandler() {
            @Override
            public String topic() {
                return "com.citypass.espacios.CriticidadClimaticaFinalizada";
            }

            @Override
            public void handle(IncomingEvent event) {
                alerts.finish(event.data());
            }
        };
    }
}
