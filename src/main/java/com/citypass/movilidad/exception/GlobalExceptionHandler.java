package com.citypass.movilidad.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.regex.Pattern;

/** Convierte cualquier excepción en una respuesta HTTP con el formato de ErrorResponse. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String UNEXPECTED_MESSAGE = "Ocurrió un error inesperado";
    private static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";
    private static final String DATA_INTEGRITY_CODE = "DATA_INTEGRITY_VIOLATION";
    private static final String CONCURRENT_UPDATE_CODE = "CONCURRENT_UPDATE";

    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cntrl}]");
    private static final int MAX_LOGGED_LENGTH = 200;

    /** Excepciones de dominio: el estado y el código los define la propia excepción. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, WebRequest request) {
        String path = pathOf(request);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Excepción de dominio en {}: {}", forLog(path), forLog(ex.getMessage()));
        }
        return respond(ex.getStatus(), ex.getCode(), ex.getMessage(), path, null);
    }

    /** Parámetros de query, path o header inválidos. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                   WebRequest request) {
        List<ErrorResponse.FieldError> errors = ex.getConstraintViolations().stream()
                .map(violation -> new ErrorResponse.FieldError(
                        lastSegmentOf(violation.getPropertyPath().toString()), violation.getMessage()))
                .toList();
        return validationError(errors, "Parámetros inválidos", pathOf(request));
    }

    /** Violación de una restricción de la base (ej. clave duplicada). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex,
                                                             WebRequest request) {
        String path = pathOf(request);
        String loggedPath = forLog(path);
        // El mensaje del driver puede exponer datos internos: solo va al log.
        LOG.warn("Violación de integridad en {}", loggedPath, ex);
        return respond(HttpStatus.CONFLICT, DATA_INTEGRITY_CODE,
                "La operación entra en conflicto con datos ya existentes", path, null);
    }

    /** Otra transacción modificó el recurso primero: se puede reintentar. */
    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> handleConcurrentUpdate(Exception ex, WebRequest request) {
        String path = pathOf(request);
        String loggedPath = forLog(path);
        String loggedCause = forLog(ex.getMessage());
        LOG.warn("Conflicto de concurrencia en {}: {}", loggedPath, loggedCause);
        return respond(HttpStatus.CONFLICT, CONCURRENT_UPDATE_CODE,
                "El recurso fue modificado por otra operación, volvé a intentarlo", path, null);
    }

    /** Las de seguridad se relanzan para que Spring Security responda 401/403 (y no 500). */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public void rethrowSecurityException(RuntimeException ex) {
        throw ex;
    }

    /** Cualquier otro error: 500 con mensaje genérico; el detalle va al log. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, WebRequest request) {
        String path = pathOf(request);
        String loggedPath = forLog(path);
        LOG.error("Error no controlado en {}", loggedPath, ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_CODE, UNEXPECTED_MESSAGE, path, null);
    }

    /** Body inválido: un error por campo. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<ErrorResponse.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ErrorResponse.FieldError(fieldError.getField(),
                        fieldError.getDefaultMessage()))
                .toList();
        return asObject(validationError(errors, "Datos inválidos", pathOf(request)));
    }

    /** Parámetros del controller que no pasan sus anotaciones de validación. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<ErrorResponse.FieldError> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ErrorResponse.FieldError(
                                result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        return asObject(validationError(errors, "Parámetros inválidos", pathOf(request)));
    }

    /** Reemplaza el cuerpo que arma Spring para sus propias excepciones por ErrorResponse. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String path = pathOf(request);
        if (status.is5xxServerError()) {
            String loggedPath = forLog(path);
            LOG.error("Error no controlado en {}", loggedPath, ex);
        } else if (LOG.isDebugEnabled()) {
            LOG.debug("Solicitud rechazada en {}: {}", forLog(path), forLog(ex.toString()));
        }
        ErrorResponse error = ErrorResponse.of(status, codeOf(status), messageOf(ex, status), path);
        return new ResponseEntity<>(error, headers, status);
    }

    private ResponseEntity<ErrorResponse> validationError(List<ErrorResponse.FieldError> errors,
                                                          String fallbackMessage, String path) {
        String message = errors.isEmpty()
                ? fallbackMessage
                : errors.getFirst().field() + ": " + errors.getFirst().message();
        return respond(HttpStatus.BAD_REQUEST, ValidationException.CODE, message, path, errors);
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String code, String message,
                                                  String path, List<ErrorResponse.FieldError> errors) {
        return ResponseEntity.status(status).body(ErrorResponse.of(status, code, message, path, errors));
    }

    // Mensajes propios para las excepciones de Spring: los originales exponen detalles internos.
    private String messageOf(Exception ex, HttpStatus status) {
        return switch (ex) {
            case HttpMessageNotReadableException ignored ->
                    "El cuerpo de la solicitud está ausente o contiene valores inválidos";
            case MissingRequestHeaderException missing ->
                    "Falta el header obligatorio " + missing.getHeaderName();
            case MissingServletRequestParameterException missing ->
                    "Falta el parámetro obligatorio '" + missing.getParameterName() + "'";
            case MethodArgumentTypeMismatchException mismatch ->
                    "El parámetro '" + mismatch.getName() + "' no tiene un valor válido";
            case HttpRequestMethodNotSupportedException unsupported ->
                    "El método " + unsupported.getMethod() + " no está permitido en este recurso";
            case HttpMediaTypeNotSupportedException ignored ->
                    "El tipo de contenido de la solicitud no está soportado";
            case HttpMediaTypeNotAcceptableException ignored ->
                    "No se puede generar una respuesta en el formato solicitado";
            case NoResourceFoundException ignored -> "El recurso solicitado no existe";
            case NoHandlerFoundException ignored -> "El recurso solicitado no existe";
            case ErrorResponseException problem -> detailOrDefault(problem, status);
            default -> status.is5xxServerError() ? UNEXPECTED_MESSAGE : status.getReasonPhrase();
        };
    }

    private String detailOrDefault(ErrorResponseException ex, HttpStatus status) {
        String detail = ex.getBody().getDetail();
        return detail == null || detail.isBlank() ? status.getReasonPhrase() : detail;
    }

    // Código de error a partir del estado HTTP, para excepciones que no son de dominio.
    private String codeOf(HttpStatus status) {
        return status.is5xxServerError() ? INTERNAL_ERROR_CODE : status.name();
    }

    private String pathOf(WebRequest request) {
        if (request instanceof ServletWebRequest servletRequest) {
            return servletRequest.getRequest().getRequestURI();
        }
        return request.getDescription(false);
    }

    // Quita saltos de línea y caracteres de control para evitar inyección en el log.
    private String forLog(String value) {
        if (value == null) {
            return "";
        }
        String flattened = CONTROL_CHARACTERS.matcher(value).replaceAll("_");
        return flattened.length() <= MAX_LOGGED_LENGTH ? flattened
                : flattened.substring(0, MAX_LOGGED_LENGTH) + "...";
    }

    // Deja solo el nombre del parámetro (ej. de findNearby.lat queda lat).
    private String lastSegmentOf(String propertyPath) {
        return propertyPath.substring(propertyPath.lastIndexOf('.') + 1);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Object> asObject(ResponseEntity<ErrorResponse> response) {
        return (ResponseEntity<Object>) (ResponseEntity<?>) response;
    }
}
