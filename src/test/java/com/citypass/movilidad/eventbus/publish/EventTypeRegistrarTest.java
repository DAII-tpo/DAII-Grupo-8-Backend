package com.citypass.movilidad.eventbus.publish;

import com.citypass.movilidad.eventbus.EventBusTestProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventTypeRegistrarTest {

    private EventGatewayClient gatewayClient;
    private ResourcePatternResolver resolver;
    private EventTypeRegistrar registrar;

    @BeforeEach
    void setUp() {
        gatewayClient = mock(EventGatewayClient.class);
        resolver = mock(ResourcePatternResolver.class);
        registrar = new EventTypeRegistrar(gatewayClient, resolver, new ObjectMapper(),
                EventBusTestProperties.properties());
    }

    @Test
    void registraSoloLosEventTypesQueNoExisten() throws IOException {
        when(resolver.getResources(EventTypeRegistrar.LOCATION)).thenReturn(new Resource[]{
                definition("{\"name\": \"ViajeIniciado\", \"fields\": []}"),
                definition("{\"name\": \"ViajeFinalizado\", \"fields\": []}")});
        when(gatewayClient.eventTypeExists("com.citypass.movilidad.ViajeIniciado")).thenReturn(true);
        when(gatewayClient.eventTypeExists("com.citypass.movilidad.ViajeFinalizado")).thenReturn(false);

        registrar.registerEventTypes();

        verify(gatewayClient, never()).registerEventType(contains("ViajeIniciado"));
        verify(gatewayClient).registerEventType(contains("ViajeFinalizado"));
    }

    @Test
    void unErrorConUnEventTypeNoFrenaALosDemas() throws IOException {
        when(resolver.getResources(EventTypeRegistrar.LOCATION)).thenReturn(new Resource[]{
                definition("{\"name\": \"ViajeIniciado\", \"fields\": []}"),
                definition("{\"name\": \"ViajeFinalizado\", \"fields\": []}")});
        when(gatewayClient.eventTypeExists("com.citypass.movilidad.ViajeIniciado"))
                .thenThrow(new EventGatewayException("caído", 0, true, null));
        when(gatewayClient.eventTypeExists("com.citypass.movilidad.ViajeFinalizado")).thenReturn(false);

        registrar.registerEventTypes();

        verify(gatewayClient).registerEventType(contains("ViajeFinalizado"));
    }

    @Test
    void ignoraDefinicionesInvalidas() throws IOException {
        when(resolver.getResources(EventTypeRegistrar.LOCATION)).thenReturn(new Resource[]{
                definition("no es json"), definition("{\"fields\": []}")});

        registrar.registerEventTypes();

        verify(gatewayClient, never()).eventTypeExists(any());
    }

    private static Resource definition(String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
    }
}
