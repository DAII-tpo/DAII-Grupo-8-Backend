package com.citypass.movilidad.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Hash de las contraseñas de los ciudadanos.
 *
 * <p>En su propia clase y no en {@code SecurityConfig} porque ese test la instancia con
 * {@code new} y conviene no cargarla de más. BCrypt llega transitivo por
 * {@code spring-security-crypto}, así que no agrega dependencias.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
