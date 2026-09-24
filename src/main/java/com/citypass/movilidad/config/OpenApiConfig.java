package com.citypass.movilidad.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Datos generales de la documentación OpenAPI / Swagger. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI movilidadOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CityPass+ Movilidad Backend")
                        .description("Contrato REST del módulo de Movilidad Urbana Inteligente (Grupo 8). "
                                + "La autenticación y autorización son provistas por el módulo externo "
                                + "de Login Federado y no forman parte de este contrato.")
                        .version("v1"));
    }
}
