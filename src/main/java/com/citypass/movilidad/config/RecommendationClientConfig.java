package com.citypass.movilidad.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Cliente HTTP del servicio de recomendación (MOV-042). Se arma acá y no dentro del cliente
 * para que los tests puedan inyectar un RestClient con un servidor simulado.
 */
@Configuration
public class RecommendationClientConfig {

    @Bean
    public RestClient recommendationRestClient(RecommendationProperties properties) {
        // HTTP/1.1 explícito: uvicorn no negocia el upgrade a HTTP/2 que el cliente del JDK
        // intenta por defecto, y la conexión queda esperando hasta el timeout.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMillis()))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMillis()));

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
