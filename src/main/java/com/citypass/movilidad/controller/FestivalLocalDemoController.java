package com.citypass.movilidad.controller;

import com.citypass.movilidad.eventbus.consume.InvalidEventException;
import com.citypass.movilidad.service.FestivalRestockService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Inyecta el data de E1 para ensayar el flujo sin levantar Kafka. */
@RestController
@Profile("festival-local")
public class FestivalLocalDemoController {
    private final FestivalRestockService restock;

    public FestivalLocalDemoController(FestivalRestockService restock) {
        this.restock = restock;
    }

    @PostMapping("/api/v1/dev/festival/actividad-creada")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void simulate(@RequestBody JsonNode data) {
        try {
            restock.planFromActivity(data);
        } catch (InvalidEventException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
