package com.citypass.movilidad.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Contacto con el IdP del Login Federado (Grupo 2) para traer el perfil de la persona.
 *
 * <p>El access token no trae email ni apellido (no existen esos claims), pero el contrato de
 * eventos del bus los exige como obligatorios, así que hay que pedírselos al IdP.
 *
 * <p>Prefijo propio y no {@code movilidad.auth} a propósito: {@link AuthProperties} se construye
 * posicionalmente en varios tests, y agregarle componentes los rompería sin ninguna ganancia.
 */
@ConfigurationProperties(prefix = "movilidad.idp")
public record IdpProperties(

        /** Perfil de quien presenta el token: uid, employeeNumber, fullName, email y groups. */
        @DefaultValue("https://api.citypass-login.pp.ua/me") String userInfoUri,

        /** Apaga la consulta sin tocar código, para desarrollo sin salida a internet. */
        @DefaultValue("true") boolean enabled,

        @DefaultValue("1000") int connectTimeoutMillis,
        @DefaultValue("2000") int readTimeoutMillis,

        /**
         * Tras un fallo no se reintenta antes de esto. Sin esta espera, una caída del IdP
         * produciría una llamada HTTP fallida por cada request de cada persona incompleta.
         */
        @DefaultValue("PT10M") Duration retryCooldown) {
}
