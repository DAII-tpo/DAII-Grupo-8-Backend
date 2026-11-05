package com.citypass.movilidad.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Plazos del pedido de ayuda (SOS) durante un viaje. */
@ConfigurationProperties(prefix = "movilidad.emergency")
public record EmergencyProperties(

        /** Ventana para cancelar un SOS enviado por error. Durante ese tiempo no se avisa a Emergencias. */
        @DefaultValue("10s") Duration cancelWindow,

        /** Plazo para responder el cuestionario después de la ventana de cancelación. Vencido, se avisa igual. */
        @DefaultValue("60s") Duration triageWindow) {
}
