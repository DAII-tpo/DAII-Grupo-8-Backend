package com.citypass.movilidad.controller;

import com.citypass.movilidad.eventbus.consume.InvalidEventException;
import com.citypass.movilidad.service.FestivalRestockService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
    @PostMapping("/api/v1/dev/festival/actividad-modificada")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void simulateChange(@RequestBody JsonNode data) {
        try {
            restock.applyActivityChange(data);
        } catch (InvalidEventException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
    public record DeadlineChange(@NotBlank @Size(max = 128) String actividadId,
                                 @NotNull Long fechaHoraFinalizacion) {}

    @PostMapping("/api/v1/dev/festival/horario-modificado")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void simulateDeadlineChange(@Valid @RequestBody DeadlineChange change) {
        restock.changeQaDeadline(change.actividadId(), change.fechaHoraFinalizacion());
    }

    public record LocationChange(@NotBlank @Size(max = 128) String actividadId,
                                 @NotNull Double lat,
                                 @NotNull Double lng) {}

    @PostMapping("/api/v1/dev/festival/ubicacion-modificada")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void simulateLocationChange(@Valid @RequestBody LocationChange change) {
        restock.changeQaLocation(change.actividadId(), change.lat(), change.lng());
    }

}
