package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.PagedResponse;
import com.citypass.movilidad.dto.TripEndRequest;
import com.citypass.movilidad.dto.TripResponse;
import com.citypass.movilidad.dto.TripStartRequest;
import com.citypass.movilidad.exception.ErrorResponse;
import com.citypass.movilidad.service.TripService;
import com.citypass.movilidad.validation.EntityId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** Endpoints de viajes del usuario. */
@RestController
@RequestMapping("/api/v1/trips")
@Validated
@Tag(name = "Viajes",
        description = "Inicio, consulta del viaje activo, finalización e historial de viajes")
public class TripController {

    // TODO: reemplazar por la identidad del JWT cuando esté integrado el Login Federado.
    static final String USER_HEADER = "X-User-Id";
    private static final String USER_HEADER_DESCRIPTION =
            "ID del usuario actual. Mecanismo temporal hasta integrar el login federado de Grupo 2";

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    /** POST /trips: inicia un viaje. */
    @PostMapping
    @Operation(summary = "Iniciar un viaje",
            description = "Retira una bicicleta AVAILABLE: crea el viaje ACTIVE y la bicicleta pasa a IN_USE")
    @ApiResponse(responseCode = "201", description = "Viaje iniciado",
            content = @Content(schema = @Schema(implementation = TripResponse.class)))
    @ApiResponse(responseCode = "400", description = "Falta el header de usuario o el cuerpo es inválido",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "El usuario o la bicicleta no existen",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409",
            description = "Usuario no habilitado, con un viaje activo, o bicicleta no disponible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<TripResponse> start(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(USER_HEADER) @EntityId Long userId,
            @Valid @RequestBody TripStartRequest request) {
        TripResponse created = tripService.startTrip(userId, request);
        return ResponseEntity.created(URI.create("/api/v1/trips/" + created.id())).body(created);
    }

    /** GET /trips/active: viaje activo del usuario (204 si no tiene). */
    @GetMapping("/active")
    @Operation(summary = "Consultar el viaje activo del usuario",
            description = "Devuelve bicicleta, estación de origen y hora de inicio del viaje en curso")
    @ApiResponse(responseCode = "200", description = "El usuario tiene un viaje activo",
            content = @Content(schema = @Schema(implementation = TripResponse.class)))
    @ApiResponse(responseCode = "204", description = "El usuario no tiene ningún viaje activo")
    @ApiResponse(responseCode = "400", description = "Falta el header de usuario o es inválido",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "El usuario no existe",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<TripResponse> findActive(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(USER_HEADER) @EntityId Long userId) {
        return tripService.findActiveTrip(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** GET /trips/history: historial paginado de viajes finalizados. */
    @GetMapping("/history")
    @Operation(summary = "Historial de viajes del usuario",
            description = "Devuelve los viajes finalizados del usuario, del más reciente al más antiguo, "
                    + "con origen, destino, inicio, finalización y duración. Un usuario sin viajes "
                    + "finalizados recibe una página vacía.")
    @ApiResponse(responseCode = "200", description = "Página de viajes finalizados (puede venir vacía)",
            content = @Content(schema = @Schema(implementation = PagedResponse.class)))
    @ApiResponse(responseCode = "400", description = "Falta el header de usuario o la paginación es inválida",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "El usuario no existe",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public PagedResponse<TripResponse> history(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(USER_HEADER) Long userId,

            @Parameter(description = "Número de página, empezando en 0", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Cantidad de viajes por página, entre 1 y 50", example = "10")
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return tripService.findTripHistory(userId, page, size);
    }

    /** POST /trips/{id}/end: finaliza el viaje devolviendo la bici. */
    @PostMapping("/{id}/end")
    @Operation(summary = "Finalizar un viaje",
            description = "Devuelve la bicicleta en una estación habilitada con capacidad: el viaje pasa a "
                    + "COMPLETED y la bicicleta queda AVAILABLE en la estación destino. Si alguna validación "
                    + "falla, el viaje y la bicicleta conservan su estado anterior")
    @ApiResponse(responseCode = "200", description = "Viaje finalizado",
            content = @Content(schema = @Schema(implementation = TripResponse.class)))
    @ApiResponse(responseCode = "400", description = "Falta el header de usuario o el cuerpo es inválido",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404",
            description = "El usuario, el viaje (o no pertenece al usuario), la bicicleta o la estación no existen",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409",
            description = "El viaje no está activo, la bicicleta es inconsistente con el viaje, o la estación "
                    + "no está habilitada o no tiene capacidad",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public TripResponse end(
            @Parameter(in = ParameterIn.HEADER, description = USER_HEADER_DESCRIPTION, required = true)
            @RequestHeader(USER_HEADER) @EntityId Long userId,
            @PathVariable @EntityId Long id,
            @Valid @RequestBody TripEndRequest request) {
        return tripService.endTrip(userId, id, request);
    }
}
