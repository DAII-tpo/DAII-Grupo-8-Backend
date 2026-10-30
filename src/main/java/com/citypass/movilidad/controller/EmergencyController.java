package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.EmergencyCreateRequest;
import com.citypass.movilidad.dto.EmergencyResponse;
import com.citypass.movilidad.dto.EmergencyTriageRequest;
import com.citypass.movilidad.exception.ErrorResponse;
import com.citypass.movilidad.service.EmergencyService;
import com.citypass.movilidad.validation.EntityId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** Pedido de ayuda (SOS) durante un viaje. */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Emergencias",
        description = "Pedido de ayuda durante un viaje: SOS, cancelación por error y cuestionario de triage")
public class EmergencyController {

    private static final String USER_HEADER_DESCRIPTION =
            "ID del usuario actual. Mecanismo temporal hasta integrar el login federado de Grupo 2";

    private final EmergencyService emergencyService;

    public EmergencyController(EmergencyService emergencyService) {
        this.emergencyService = emergencyService;
    }

    /** POST /trips/{tripId}/emergencies: pide ayuda durante el viaje. */
    @PostMapping("/trips/{tripId}/emergencies")
    @Operation(summary = "Pedir ayuda (SOS) durante un viaje",
            description = "Registra el pedido con la ubicación actual: el viaje pasa a EMERGENCY (pausado) y la "
                    + "bicicleta a BLOCKED. Emergencias recién se entera cuando se envía el cuestionario o vence "
                    + "su plazo, así que durante la ventana de cancelación un error no llega a nadie")
    @ApiResponse(responseCode = "201", description = "Pedido registrado",
            content = @Content(schema = @Schema(implementation = EmergencyResponse.class)))
    @ApiResponse(responseCode = "400", description = "Falta el header de usuario o la ubicación es inválida",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "El usuario o el viaje (o no pertenece al usuario) no existen",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "El viaje no está activo o ya tiene una emergencia en curso",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<EmergencyResponse> report(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(TripController.USER_HEADER) @EntityId Long userId,
            @PathVariable @EntityId Long tripId,
            @Valid @RequestBody EmergencyCreateRequest request) {
        EmergencyResponse created = emergencyService.report(userId, tripId, request);
        return ResponseEntity.created(URI.create("/api/v1/emergencies/" + created.id())).body(created);
    }

    /** GET /emergencies/current: emergencia en curso del usuario (204 si no tiene). */
    @GetMapping("/emergencies/current")
    @Operation(summary = "Consultar la emergencia en curso",
            description = "Devuelve la emergencia del viaje pausado del usuario, para retomar la pantalla al recargar")
    @ApiResponse(responseCode = "200", description = "El usuario tiene una emergencia en curso",
            content = @Content(schema = @Schema(implementation = EmergencyResponse.class)))
    @ApiResponse(responseCode = "204", description = "El usuario no tiene ninguna emergencia en curso")
    @ApiResponse(responseCode = "404", description = "El usuario no existe",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<EmergencyResponse> findCurrent(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(TripController.USER_HEADER) @EntityId Long userId) {
        return emergencyService.findCurrent(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** POST /emergencies/{id}/cancel: cancela un SOS enviado por error. */
    @PostMapping("/emergencies/{id}/cancel")
    @Operation(summary = "Cancelar un pedido de ayuda",
            description = "Solo dentro de la ventana de cancelación: el viaje vuelve a ACTIVE, la bicicleta a "
                    + "IN_USE y no se avisa a Emergencias")
    @ApiResponse(responseCode = "200", description = "Pedido cancelado",
            content = @Content(schema = @Schema(implementation = EmergencyResponse.class)))
    @ApiResponse(responseCode = "404", description = "La emergencia no existe o no pertenece al usuario",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Venció la ventana de cancelación o ya se resolvió",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public EmergencyResponse cancel(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(TripController.USER_HEADER) @EntityId Long userId,
            @PathVariable @EntityId Long id) {
        return emergencyService.cancel(userId, id);
    }

    /** POST /emergencies/{id}/triage: respuestas del cuestionario; avisa a Emergencias. */
    @PostMapping("/emergencies/{id}/triage")
    @Operation(summary = "Enviar el cuestionario de triage",
            description = "Después de la ventana de cancelación. Calcula la prioridad sugerida y publica el evento "
                    + "EmergenciaBicicleta en el bus. Si no llega antes del plazo, el evento sale igual, sin "
                    + "respuestas y con la prioridad del peor caso")
    @ApiResponse(responseCode = "200", description = "Cuestionario enviado y Emergencias avisada",
            content = @Content(schema = @Schema(implementation = EmergencyResponse.class)))
    @ApiResponse(responseCode = "400", description = "Falta alguna respuesta",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "La emergencia no existe o no pertenece al usuario",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409",
            description = "Todavía se puede cancelar, o la emergencia ya fue enviada o cancelada",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public EmergencyResponse triage(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(TripController.USER_HEADER) @EntityId Long userId,
            @PathVariable @EntityId Long id,
            @Valid @RequestBody EmergencyTriageRequest request) {
        return emergencyService.submitTriage(userId, id, request);
    }
}
