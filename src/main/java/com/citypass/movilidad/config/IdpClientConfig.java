package com.citypass.movilidad.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** RestClient del IdP. Timeouts cortos: si no contesta, el alta sigue sin el perfil. */
@Configuration
public class IdpClientConfig {

    @Bean
    public RestClient idpRestClient(IdpProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMillis()))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMillis()));

        // Sin baseUrl: user-info-uri es absoluta, igual que las URLs del bus de eventos.
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
