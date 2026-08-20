package com.citypass.movilidad.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI movilidadOpenApi() {
        return new OpenAPI().info(new Info()
                .title("CityPass+ Movilidad Backend")
                .description("API del módulo de Movilidad Urbana Inteligente (Grupo 8) para el proyecto CityPass+")
                .version("v1"));
    }
}
