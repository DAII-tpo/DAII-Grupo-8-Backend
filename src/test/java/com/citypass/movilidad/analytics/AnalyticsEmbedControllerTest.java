package com.citypass.movilidad.analytics;

import com.citypass.movilidad.config.AuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Incluso con permitAll y method security desactivado, la ruta no admite usuarios demo. */
@WebMvcTest(value = AnalyticsEmbedController.class, properties = "security.local.enabled=true")
class AnalyticsEmbedControllerTest {
    private static final String PATH = "/api/v1/admin/analytics/embed";
    @Autowired MockMvc mvc;
    @MockitoBean AnalyticsEmbedClient client;
    @MockitoBean AuthProperties auth;

    @BeforeEach void setup() {
        when(auth.issuer()).thenReturn("idp-human");
        when(auth.requiredTokenUse()).thenReturn("human");
        when(client.fetch(anyString())).thenReturn(embed(MediaType.TEXT_HTML, "<html>dashboard</html>"));
    }

    private AnalyticsEmbedClient.Embed embed(MediaType type, String html) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.set("Content-Security-Policy", "default-src 'self'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        return new AnalyticsEmbedClient.Embed(type, html.getBytes(java.nio.charset.StandardCharsets.UTF_8), headers);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor token(String issuer, String use, String role) {
        return jwt().jwt(j -> j.issuer(issuer).claim("token_use", use))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test void administradorRecibeConfirmacionSinCache() throws Exception {
        mvc.perform(get(PATH).with(token("idp-human", "human", "ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string("<html>dashboard</html>"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test void ciudadanoNoPuedeUsarNiUnRolAdminInyectado() throws Exception {
        mvc.perform(get(PATH).with(token("citizen", "human", "ADMIN"))).andExpect(status().isForbidden());
        verify(client, never()).fetch(anyString());
    }

    @Test void empleadoSinAdminNoPuedeConsultarBi() throws Exception {
        mvc.perform(get(PATH).with(token("idp-human", "human", "USER"))).andExpect(status().isForbidden());
        verify(client, never()).fetch(anyString());
    }

    @Test void unServicioNoPuedeIngresarComoPersona() throws Exception {
        mvc.perform(get(PATH).with(token("idp-human", "service", "ADMIN"))).andExpect(status().isForbidden());
        verify(client, never()).fetch(anyString());
    }

    @Test void elTokenDeServicioNoDaAccesoAunqueSeConfigureMalElUsoHumano() throws Exception {
        when(auth.requiredTokenUse()).thenReturn("service");
        mvc.perform(get(PATH).with(token("idp-human", "service", "ADMIN"))).andExpect(status().isForbidden());
        verify(client, never()).fetch(anyString());
    }

    @Test void anonimoDa401AunqueElModoLocalAbraElRestoDeLaApi() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        verify(client, never()).fetch(anyString());
    }

    @Test void identidadDeDesarrolloOHeaderDeRolNoSuplenUnJwt() throws Exception {
        mvc.perform(get(PATH).with(user("demo").roles("ADMIN")).header("X-User-Id", "1"))
                .andExpect(status().isUnauthorized());
        verify(client, never()).fetch(anyString());
    }

    @Test void transmiteHtmlConCabecerasPrivadas() throws Exception {
        String html = "<html><body>Tablero provisto por BI</body></html>";
        when(client.fetch(anyString())).thenReturn(embed(MediaType.TEXT_HTML, html));
        mvc.perform(get(PATH).with(token("idp-human", "human", "ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(html))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Content-Security-Policy", "default-src 'self'"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test void unaFallaDeBiNoSePresentaComoTokenHumanoVencido() throws Exception {
        when(client.fetch(anyString())).thenThrow(new AnalyticsException(HttpStatus.BAD_GATEWAY, "ANALYTICS_UPSTREAM", "Analítica no disponible"));
        mvc.perform(get(PATH).with(token("idp-human", "human", "ADMIN")))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("ANALYTICS_UPSTREAM"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }
}
