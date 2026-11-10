package com.citypass.movilidad;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Punto de entrada del backend de Movilidad. */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class MovilidadBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MovilidadBackendApplication.class, args);
    }
}
