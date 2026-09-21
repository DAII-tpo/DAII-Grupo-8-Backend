package com.citypass.movilidad.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void devuelveBadRequestConElPrimerErrorDeValidacion() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("objeto", "nombre", "no puede estar vacío");
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/algo");

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo("Bad Request");
        assertThat(response.getBody().message()).isEqualTo("nombre: no puede estar vacío");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/algo");
    }

    @Test
    void devuelveMensajeGenericoSiNoHayErroresDeCampo() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of());

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/algo");

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex, request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Datos inválidos");
    }

    @Test
    void devuelveInternalServerErrorParaExcepcionesGenericas() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/otra");

        ResponseEntity<ErrorResponse> response = handler.handleGeneric(new RuntimeException("boom"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Ocurrió un error inesperado");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/otra");
    }

    @Test
    void devuelveBadRequestConMultiplesErroresTomandoElPrimero() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError primerError = new FieldError("obj", "codigo", "es obligatorio");
        FieldError segundoError = new FieldError("obj", "descripcion", "demasiado larga");
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(primerError, segundoError));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/bicis");

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("codigo: es obligatorio");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/bicis");
    }

    @Test
    void devuelveBadRequestCuandoMensajeDeErrorEsNulo() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("obj", "estado", null);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/bicis");

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("estado: null");
    }

    @Test
    void devuelveInternalServerErrorParaNullPointerException() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/fallo");

        ResponseEntity<ErrorResponse> response = handler.handleGeneric(new NullPointerException("referencia nula"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().error()).isEqualTo("Internal Server Error");
        assertThat(response.getBody().message()).isEqualTo("Ocurrió un error inesperado");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/fallo");
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Test
    void errorResponseEstructuraYPropiedadesCorrectas() {
        java.time.Instant ahora = java.time.Instant.now();
        ErrorResponse errorResponse = new ErrorResponse(ahora, 400, "Bad Request", "Error de prueba", "/test");

        assertThat(errorResponse.timestamp()).isEqualTo(ahora);
        assertThat(errorResponse.status()).isEqualTo(400);
        assertThat(errorResponse.error()).isEqualTo("Bad Request");
        assertThat(errorResponse.message()).isEqualTo("Error de prueba");
        assertThat(errorResponse.path()).isEqualTo("/test");
    }
}
