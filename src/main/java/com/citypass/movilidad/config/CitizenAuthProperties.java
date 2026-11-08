package com.citypass.movilidad.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Login local de ciudadanos. El IdP del Grupo 2 es solo para personal municipal, así que los
 * usuarios finales los autentica este módulo con tokens que firma él mismo.
 *
 * <p>Prefijo propio y no {@code movilidad.auth} para no agregarle componentes a
 * {@link AuthProperties}, que varios tests construyen posicionalmente.
 */
@ConfigurationProperties(prefix = "movilidad.citizen-auth")
public record CitizenAuthProperties(

        /** {@code iss} de nuestros tokens. Distinto al del IdP: es lo que distingue a las dos poblaciones. */
        @DefaultValue("https://movilidad.citypass.local") String issuer,

        /** Mismo audience que el federado: la separación la hacen el issuer, la clave y token_use. */
        @DefaultValue("citypass-movilidad-api") String audience,

        /** Secreto HS256 en base64url, de 32 bytes o más. Vacío: ver {@link LocalSigningKey}. */
        String secret,

        @DefaultValue("PT30M") Duration accessTokenTtl,
        @DefaultValue("P30D") Duration refreshTokenTtl,

        /** Intentos fallidos dentro de la ventana que disparan el bloqueo. */
        @DefaultValue("5") int maxFailedAttempts,

        /** Ventana en la que se cuentan los fallos; pasada, el contador se reinicia. */
        @DefaultValue("PT15M") Duration failedAttemptWindow,

        /** Cuánto dura el bloqueo una vez alcanzado el máximo. */
        @DefaultValue("PT10M") Duration lockDuration) {
}
