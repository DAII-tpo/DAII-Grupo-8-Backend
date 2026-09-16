package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.TripEndRequest;
import com.citypass.movilidad.dto.TripResponse;
import com.citypass.movilidad.dto.TripStartRequest;
import com.citypass.movilidad.service.TripService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/trips")
@Tag(name = "Viajes", description = "Inicio, consulta del viaje activo y finalización de viajes")
public class TripController {

    // TODO: reemplazar por contexto de seguridad de Grupo 2. Mientras no esté integrado el login
    // federado (LDAP + JWT), el usuario actual se identifica con este header.
    static final String USER_HEADER = "X-User-Id";
    private static final String USER_HEADER_DESCRIPTION =
            "ID del usuario actual. Mecanismo temporal hasta integrar el login federado de Grupo 2";

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    @PostMapping
    @Operation(summary = "Iniciar un viaje",
            description = "Retira una bicicleta AVAILABLE: crea el viaje ACTIVE y la bicicleta pasa a IN_USE")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Viaje iniciado"),
            @ApiResponse(responseCode = "400", description = "Falta el header de usuario o el cuerpo es inválido"),
            @ApiResponse(responseCode = "404", description = "El usuario o la bicicleta no existen"),
            @ApiResponse(responseCode = "409",
                    description = "Usuario no habilitado, con un viaje activo, o bicicleta no disponible")
    })
    public ResponseEntity<TripResponse> start(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(USER_HEADER) Long userId,
            @Valid @RequestBody TripStartRequest request) {
        TripResponse created = tripService.startTrip(userId, request);
        return ResponseEntity.created(URI.create("/api/v1/trips/" + created.id())).body(created);
    }

    @GetMapping("/active")
    @Operation(summary = "Consultar el viaje activo del usuario",
            description = "Devuelve bicicleta, estación de origen y hora de inicio del viaje en curso")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "El usuario tiene un viaje activo"),
            @ApiResponse(responseCode = "204", description = "El usuario no tiene ningún viaje activo"),
            @ApiResponse(responseCode = "400", description = "Falta el header de usuario"),
            @ApiResponse(responseCode = "404", description = "El usuario no existe")
    })
    public ResponseEntity<TripResponse> findActive(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(USER_HEADER) Long userId) {
        return tripService.findActiveTrip(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/end")
    @Operation(summary = "Finalizar un viaje",
            description = "Devuelve la bicicleta en una estación habilitada con capacidad: el viaje pasa a "
                    + "COMPLETED y la bicicleta queda AVAILABLE en la estación destino")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Viaje finalizado"),
            @ApiResponse(responseCode = "400", description = "Falta el header de usuario o el cuerpo es inválido"),
            @ApiResponse(responseCode = "404",
                    description = "El usuario, el viaje (o no pertenece al usuario) o la estación no existen"),
            @ApiResponse(responseCode = "409",
                    description = "El viaje no está activo, o la estación no está habilitada o no tiene capacidad")
    })
    public TripResponse end(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(USER_HEADER) Long userId,
            @PathVariable Long id,
            @Valid @RequestBody TripEndRequest request) {
        return tripService.endTrip(userId, id, request);
    }
}
