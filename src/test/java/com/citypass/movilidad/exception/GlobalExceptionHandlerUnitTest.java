package com.citypass.movilidad.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Casos del contrato de error que no se pueden provocar con una solicitud real en
 * GlobalExceptionHandlerTest: excepciones que Spring MVC nunca llega a lanzar en un test de
 * MockMvc, o que dependen de un WebRequest que no es servlet.
 */
class GlobalExceptionHandlerUnitTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void restaurarNivelDeLog() {
        nivelDeLog(null);
    }

    private static void nivelDeLog(Level level) {
        ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(GlobalExceptionHandler.class)
                .setLevel(level);
    }

    private static WebRequest requestTo(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return new ServletWebRequest(request);
    }

    @Test
    void traduceLasViolacionesDeRestriccionesAUnDetallePorParametro() {
        ConstraintViolationException ex = new ConstraintViolationException(
                Set.of(violation("findNearby.lat", "debe ser una latitud entre -90.0 y 90.0")));

        ResponseEntity<ErrorResponse> response =
                handler.handleConstraintViolation(ex, requestTo("/api/v1/stations/nearby"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        // Del path de la violación solo interesa el parámetro, no el método que lo declaró.
        assertThat(response.getBody().errors()).singleElement()
                .satisfies(error -> assertThat(error.field()).isEqualTo("lat"));
        assertThat(response.getBody().message()).startsWith("lat: ");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/stations/nearby");
    }

    @Test
    void usaUnMensajeGenericoCuandoNoHayNingunDetalleDeValidacion() {
        ConstraintViolationException ex = new ConstraintViolationException(Set.of());

        ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(ex, requestTo("/api/v1/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Parámetros inválidos");
        assertThat(response.getBody().errors()).isNull();
    }

    @Test
    void devuelveConflictAnteUnConflictoDeConcurrencia() {
        WebRequest request = requestTo("/api/v1/trips/7/end");

        for (Exception ex : List.of(new OptimisticLockingFailureException("version mismatch"),
                new PessimisticLockingFailureException("lock timeout"))) {
            ResponseEntity<ErrorResponse> response = handler.handleConcurrentUpdate(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("CONCURRENT_UPDATE");
            assertThat(response.getBody().message())
                    .isEqualTo("El recurso fue modificado por otra operación, volvé a intentarlo");
            // El detalle del fallo de lock queda en el log, no en la respuesta.
            assertThat(response.getBody().message()).doesNotContain("lock", "version");
        }
    }

    @Test
    void devuelveLasExcepcionesDeSeguridadAlFiltroEnLugarDeConvertirlasEnRespuesta() {
        AccessDeniedException denied = new AccessDeniedException("sin permisos");

        assertThatThrownBy(() -> handler.rethrowSecurityException(denied)).isSameAs(denied);
    }

    @Test
    void usaUnMensajePropioParaLasExcepcionesDeSpringQueNoLleganPorUnaSolicitudReal() {
        assertThat(messageFor(new HttpMediaTypeNotAcceptableException("no acceptable"),
                HttpStatus.NOT_ACCEPTABLE))
                .isEqualTo("No se puede generar una respuesta en el formato solicitado");

        assertThat(messageFor(new NoHandlerFoundException("GET", "/api/v1/nada", HttpHeaders.EMPTY),
                HttpStatus.NOT_FOUND))
                .isEqualTo("El recurso solicitado no existe");
    }

    @Test
    void conservaElDetalleDeUnErrorResponseExceptionYCaeEnLaFraseEstandarSiNoLoTrae() {
        ProblemDetail conDetalle = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "detalle propio");
        assertThat(messageFor(new ErrorResponseException(HttpStatus.BAD_REQUEST, conDetalle, null),
                HttpStatus.BAD_REQUEST))
                .isEqualTo("detalle propio");

        ProblemDetail sinDetalle = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        sinDetalle.setDetail("   ");
        assertThat(messageFor(new ErrorResponseException(HttpStatus.BAD_REQUEST, sinDetalle, null),
                HttpStatus.BAD_REQUEST))
                .isEqualTo("Bad Request");
    }

    @Test
    void ocultaElMotivoYUsaElCodigoInternoCuandoElEstadoEsDeServidor() {
        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new IllegalStateException("detalle interno"), null, HttpHeaders.EMPTY,
                HttpStatus.INTERNAL_SERVER_ERROR, requestTo("/api/v1/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.message()).isEqualTo("Ocurrió un error inesperado");
    }

    @Test
    void usaUnEstadoDeServidorCuandoElCodigoRecibidoNoEsUnEstadoConocido() {
        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new IllegalStateException("boom"), null, HttpHeaders.EMPTY,
                org.springframework.http.HttpStatusCode.valueOf(799), requestTo("/api/v1/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void describeLaSolicitudCuandoElWebRequestNoEsUnaSolicitudServlet() {
        WebRequest noServlet = mock(WebRequest.class);
        when(noServlet.getDescription(false)).thenReturn("uri=/api/v1/otro-transporte");

        ResponseEntity<ErrorResponse> response =
                handler.handleApiException(new ResourceNotFoundException("no existe"), noServlet);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo("uri=/api/v1/otro-transporte");
    }

    /** Un path con saltos de línea no debe poder inyectar entradas falsas en el log. */
    @Test
    void aplanaElTextoDeOrigenExternoAntesDeLlevarloAlLog() {
        String inyeccion = "/api/v1/x\nINFO  usuario admin creado";

        ResponseEntity<ErrorResponse> response =
                handler.handleDataIntegrity(new org.springframework.dao.DataIntegrityViolationException(inyeccion),
                        requestTo(inyeccion));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("La operación entra en conflicto con datos ya existentes");
    }

    @Test
    void soportaUnaExcepcionSinMensajeAlLlevarlaAlLog() {
        ResponseEntity<ErrorResponse> response =
                handler.handleConcurrentUpdate(new OptimisticLockingFailureException(null), requestTo("/api/v1/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void recortaElTextoDemasiadoLargoAntesDeLlevarloAlLog() {
        String pathLargo = "/api/v1/" + "x".repeat(500);

        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrity(
                new org.springframework.dao.DataIntegrityViolationException("duplicado"), requestTo(pathLargo));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // El recorte es solo para el log: la respuesta conserva el path completo.
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo(pathLargo);
    }

    @Test
    void registraElDetalleEnDebugSinCambiarLaRespuesta() {
        nivelDeLog(Level.DEBUG);

        ResponseEntity<ErrorResponse> dominio =
                handler.handleApiException(new BusinessRuleException("regla"), requestTo("/api/v1/x"));
        ResponseEntity<Object> framework = handler.handleExceptionInternal(
                new IllegalStateException("boom"), null, HttpHeaders.EMPTY, HttpStatus.BAD_REQUEST,
                requestTo("/api/v1/x"));

        assertThat(dominio.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(framework.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String messageFor(Exception ex, HttpStatus status) {
        ResponseEntity<Object> response = handler.handleExceptionInternal(
                ex, null, HttpHeaders.EMPTY, status, requestTo("/api/v1/x"));
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body).isNotNull();
        return body.message();
    }

    @SuppressWarnings("unchecked")
    private static ConstraintViolation<Object> violation(String propertyPath, String message) {
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn(propertyPath);
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn(message);
        return violation;
    }
}
