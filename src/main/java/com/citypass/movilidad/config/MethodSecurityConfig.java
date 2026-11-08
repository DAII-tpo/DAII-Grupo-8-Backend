package com.citypass.movilidad.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Habilita {@code @PreAuthorize} solo en modo seguro.
 *
 * <p>Va aparte de {@link SecurityConfig} a propósito: la cadena local es {@code permitAll} y no
 * autentica a nadie, así que con method security activo ahí {@code hasRole('ADMIN')} denegaría
 * todas las operaciones de admin en desarrollo. Inerte en modo local, igual que el resto del
 * diseño de dos cadenas.
 */
@Configuration
@EnableMethodSecurity
@ConditionalOnProperty(name = "security.local.enabled", havingValue = "false")
public class MethodSecurityConfig {
}
