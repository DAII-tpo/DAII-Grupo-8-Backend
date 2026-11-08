package com.citypass.movilidad.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Datos generales de la documentación OpenAPI / Swagger. */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI movilidadOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CityPass+ Movilidad Backend")
                        .description("Contrato REST del módulo de Movilidad Urbana Inteligente (Grupo 8). "
                                + "Los tokens los emite el Login Federado (Grupo 2): el frontend loguea "
                                + "contra su POST /auth/login con el clientId citypass-movilidad-web y manda "
                                + "el access_token como Bearer. Este backend solo valida el token (firma "
                                + "RS256 vía JWKS, iss, aud, exp y token_use) y traduce los grupos LDAP del "
                                + "claim groups a los roles del módulo.")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token del Login Federado. Vive 15 minutos.")))
                // Requisito global: los endpoints públicos lo relajan desde la cadena de seguridad.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
