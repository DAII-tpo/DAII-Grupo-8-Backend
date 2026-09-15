package com.citypass.movilidad.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void publicaLaInformacionDelContratoDeMovilidad() {
        OpenAPI openApi = new OpenApiConfig().movilidadOpenApi();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("CityPass+ Movilidad Backend");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("v1");
        assertThat(openApi.getInfo().getDescription()).contains("Login Federado");
    }
}
