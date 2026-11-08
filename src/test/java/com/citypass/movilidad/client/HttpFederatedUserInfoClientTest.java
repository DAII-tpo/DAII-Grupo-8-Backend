package com.citypass.movilidad.client;

import com.citypass.movilidad.config.IdpProperties;
import com.citypass.movilidad.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * El cliente tiene un solo contrato hacia afuera: o devuelve el perfil, o devuelve vacío. Nunca
 * propaga el error del IdP, porque el perfil es un dato deseable y no un requisito para operar.
 *
 * <p>La partición de {@code fullName} se prueba directo sobre el método estático: son reglas de
 * texto y no hace falta un viaje HTTP para cada caso.
 */
class HttpFederatedUserInfoClientTest {

    private static final String ME_URL = "http://idp.test/me";
    private static final String SUB = "U000042";
    private static final String TOKEN = "el-access-token";

    /** Cuerpo real del IdP, tal como lo devolvió al probarlo. */
    private static final String OK_BODY = """
            {"uid":"delegado-mov","employeeNumber":"U000006","fullName":"Delegado Movilidad",
             "email":"delegado-mov@citypass.local","module":"movilidad","groups":["delegados"]}
            """;

    private final MutableClock clock = MutableClock.fija();
    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @AfterEach
    void tearDown() {
        server.reset();
    }

    private HttpFederatedUserInfoClient client() {
        return client(true);
    }

    private HttpFederatedUserInfoClient client(boolean enabled) {
        return new HttpFederatedUserInfoClient(builder.build(),
                new IdpProperties(ME_URL, enabled, 1000, 2000, Duration.ofMinutes(10)), clock);
    }

    @Test
    void devuelveElPerfilQueMandaElIdp() {
        server.expect(requestTo(ME_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        Optional<FederatedUserInfo> perfil = client().fetch(TOKEN, SUB);

        assertThat(perfil).isPresent();
        assertThat(perfil.get().email()).isEqualTo("delegado-mov@citypass.local");
        assertThat(perfil.get().firstName()).isEqualTo("Delegado");
        assertThat(perfil.get().lastName()).isEqualTo("Movilidad");
        server.verify();
    }

    @Test
    void devuelveVacioAnteUn401() {
        server.expect(requestTo(ME_URL)).andRespond(withUnauthorizedRequest());
        assertThat(client().fetch(TOKEN, SUB)).isEmpty();
        server.verify();
    }

    @Test
    void devuelveVacioAnteUn500() {
        server.expect(requestTo(ME_URL)).andRespond(withServerError());
        assertThat(client().fetch(TOKEN, SUB)).isEmpty();
        server.verify();
    }

    @Test
    void devuelveVacioSiElCuerpoNoEsJsonValido() {
        server.expect(requestTo(ME_URL))
                .andRespond(withSuccess("esto no es json", MediaType.APPLICATION_JSON));
        assertThat(client().fetch(TOKEN, SUB)).isEmpty();
        server.verify();
    }

    @Test
    void devuelveVacioSiElCuerpoEstaVacio() {
        server.expect(requestTo(ME_URL)).andRespond(withSuccess());
        assertThat(client().fetch(TOKEN, SUB)).isEmpty();
        server.verify();
    }

    /**
     * Sin esta espera, una caída del IdP implicaría una llamada HTTP fallida por cada request de
     * cada persona con el perfil incompleto. Se espera UN solo request para los dos intentos.
     */
    @Test
    void noReintentaDuranteElCooldown() {
        server.expect(requestTo(ME_URL)).andRespond(withServerError());
        HttpFederatedUserInfoClient client = client();

        assertThat(client.fetch(TOKEN, SUB)).isEmpty();
        clock.advance(Duration.ofMinutes(9));
        assertThat(client.fetch(TOKEN, SUB)).isEmpty();

        server.verify();
    }

    @Test
    void reintentaVencidoElCooldown() {
        server.expect(requestTo(ME_URL)).andRespond(withServerError());
        server.expect(requestTo(ME_URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));
        HttpFederatedUserInfoClient client = client();

        assertThat(client.fetch(TOKEN, SUB)).isEmpty();
        clock.advance(Duration.ofMinutes(11));
        assertThat(client.fetch(TOKEN, SUB)).isPresent();

        server.verify();
    }

    /** Un éxito limpia la espera: no queda nadie penalizado por un fallo viejo. */
    @Test
    void unExitoLimpiaLaEsperaDeEsaPersona() {
        server.expect(requestTo(ME_URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(ME_URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));
        HttpFederatedUserInfoClient client = client();

        assertThat(client.fetch(TOKEN, SUB)).isPresent();
        assertThat(client.fetch(TOKEN, SUB)).isPresent();

        server.verify();
    }

    /** La espera es por persona: el fallo de una no frena a la otra. */
    @Test
    void laEsperaNoAlcanzaAOtraPersona() {
        server.expect(requestTo(ME_URL)).andRespond(withServerError());
        server.expect(requestTo(ME_URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));
        HttpFederatedUserInfoClient client = client();

        assertThat(client.fetch(TOKEN, SUB)).isEmpty();
        assertThat(client.fetch(TOKEN, "U000099")).isPresent();

        server.verify();
    }

    @Test
    void noConsultaSiEstaDeshabilitado() {
        assertThat(client(false).fetch(TOKEN, SUB)).isEmpty();
        server.verify();
    }

    @Test
    void noConsultaSinTokenNiSinSubject() {
        HttpFederatedUserInfoClient client = client();
        assertThat(client.fetch(null, SUB)).isEmpty();
        assertThat(client.fetch("  ", SUB)).isEmpty();
        assertThat(client.fetch(TOKEN, null)).isEmpty();
        assertThat(client.fetch(TOKEN, " ")).isEmpty();
        server.verify();
    }

    @Test
    void parteElNombrePorLaUltimaPalabra() {
        FederatedUserInfo perfil = HttpFederatedUserInfoClient.toUserInfo(
                me("Juan Pablo Pérez García", "jp@citypass.local"));
        assertThat(perfil.firstName()).isEqualTo("Juan Pablo Pérez");
        assertThat(perfil.lastName()).isEqualTo("García");
    }

    @Test
    void conUnaSolaPalabraElNombreSeLlevaTodo() {
        FederatedUserInfo perfil = HttpFederatedUserInfoClient.toUserInfo(me("Admin", "a@citypass.local"));
        assertThat(perfil.firstName()).isEqualTo("Admin");
        assertThat(perfil.lastName()).isNull();
    }

    @Test
    void sinNombreDevuelveNombreYApellidoNulos() {
        FederatedUserInfo perfil = HttpFederatedUserInfoClient.toUserInfo(me(null, "a@citypass.local"));
        assertThat(perfil.firstName()).isNull();
        assertThat(perfil.lastName()).isNull();
        assertThat(perfil.email()).isEqualTo("a@citypass.local");
    }

    @Test
    void normalizaLosEspaciosSobrantes() {
        FederatedUserInfo perfil = HttpFederatedUserInfoClient.toUserInfo(me("  Ana   Pérez  ", "  "));
        assertThat(perfil.firstName()).isEqualTo("Ana");
        assertThat(perfil.lastName()).isEqualTo("Pérez");
        // Un email en blanco es tan ausente como uno nulo.
        assertThat(perfil.email()).isNull();
    }

    private static HttpFederatedUserInfoClient.MeResponse me(String fullName, String email) {
        return new HttpFederatedUserInfoClient.MeResponse(fullName, email);
    }

}
