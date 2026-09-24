package com.citypass.movilidad.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

/** Formato único de las respuestas de error de la API. Nunca incluye detalles internos. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Formato común de error de la API")
public record ErrorResponse(

        @Schema(description = "Momento en que se generó el error", example = "2026-09-08T14:30:00Z")
        Instant timestamp,

        @Schema(description = "Código de estado HTTP", example = "404")
        int status,

        @Schema(description = "Frase estándar del estado HTTP", example = "Not Found")
        String error,

        @Schema(description = "Código estable del error, para ramificar sin parsear el mensaje",
                example = "STATION_NOT_FOUND")
        String code,

        @Schema(description = "Mensaje legible para el usuario", example = "La estación con ID 99 no existe")
        String message,

        @Schema(description = "Path de la solicitud que falló", example = "/api/v1/stations/99")
        String path,

        @Schema(description = "Detalle por campo; solo presente en errores de validación")
        List<FieldError> errors
) {

    /** Error puntual sobre un campo o parámetro de la solicitud. */
    @Schema(description = "Error de validación de un campo")
    public record FieldError(
            @Schema(description = "Campo o parámetro rechazado", example = "capacity") String field,
            @Schema(description = "Motivo del rechazo", example = "debe ser mayor que 0") String message
    ) {
    }

    public static ErrorResponse of(HttpStatus status, String code, String message, String path) {
        return of(status, code, message, path, null);
    }

    public static ErrorResponse of(HttpStatus status, String code, String message, String path,
                                   List<FieldError> errors) {
        return new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                path,
                errors == null || errors.isEmpty() ? null : List.copyOf(errors));
    }
}
