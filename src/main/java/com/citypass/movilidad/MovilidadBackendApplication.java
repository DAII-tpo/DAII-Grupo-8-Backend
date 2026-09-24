package com.citypass.movilidad;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Punto de entrada del backend de Movilidad. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MovilidadBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MovilidadBackendApplication.class, args);
    }
}
