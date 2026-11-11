package com.citypass.movilidad.analytics;

import com.citypass.movilidad.config.FederatedJwtDecoders;
import com.citypass.movilidad.config.FederatedJwtValidators;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AnalyticsEmbedClientTest {
    private static final String BI = "https://analytics.example";
    private static final String TOKEN_URL = "https://identity.example/oauth/token";
    private static final String ISSUER = "identity-test";
    private static final String HTML = "<!doctype html><html><head></head><body><canvas>BI</canvas></body></html>";
    private static RSAKey key;
    private MockRestServiceServer server;
    private MutableClock clock;
    private AnalyticsEmbedClient client;
    private String secret;

    @BeforeAll static void keys() throws Exception { key = new RSAKeyGenerator(2048).keyID("test").generate(); }

    @BeforeEach void setup() throws Exception {
        clock = new MutableClock(Instant.now());
        secret = UUID.randomUUID().toString();
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                .jwtProcessorCustomizer(FederatedJwtDecoders::acceptAccessTokenTypes).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                FederatedJwtValidators.of(ISSUER, "citypass", "service"),
                jwt -> AnalyticsEmbedClient.validServiceClaims(jwt, clock)));
        client = new AnalyticsEmbedClient(properties(), builder.build(), decoder, clock);
    }

    private AnalyticsProperties properties() {
        return new AnalyticsProperties(BI, TOKEN_URL, ISSUER, "https://identity.example/jwks", "movilidad-test", secret, 2000, 5000);
    }

    private String token(Map<String, Object> overrides) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject("service-client")
                .audience("citypass").issueTime(Date.from(clock.instant().minusSeconds(5)))
                .expirationTime(Date.from(clock.instant().plusSeconds(900)))
                .claim("token_use", "service").claim("ver", 1).claim("namespace", AnalyticsEmbedClient.NAMESPACE)
                .jwtID(UUID.randomUUID().toString());
        overrides.forEach(claims::claim);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID("test").build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private void expectToken(String token, int ttl) {
        String basic = Base64.getEncoder().encodeToString(("movilidad-test:" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        server.expect(once(), requestTo(TOKEN_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + basic))
                .andExpect(content().formDataContains(Map.of("grant_type", "client_credentials")))
                .andRespond(withSuccess("{\"access_token\":\"" + token + "\",\"expires_in\":" + ttl + "}", MediaType.APPLICATION_JSON));
    }

    private void expectEmbed(String token, org.springframework.test.web.client.ResponseCreator response) {
        server.expect(once(), requestTo(BI + "/embed/movilidad")).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(request -> {
                    assertThat(request.getHeaders()).doesNotContainKeys("X-Usuario-Token", "X-User-Token", "Cookie");
                    assertThat(request.getURI().getQuery()).isNull();
                }).andRespond(response);
    }

    @Test void enviaSoloElTokenDeServicioYNormalizaLaConfirmacionSinFiltrarDatos() throws Exception {
        String token = token(Map.of());
        expectToken(token, 900);
        expectEmbed(token, withSuccess("{\"status\":\"ok\",\"access_token\":\"" + token + "\"}", MediaType.APPLICATION_JSON));
        var response = client.fetch();
        assertThat(response.type()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.body()).isEqualTo("{\"status\":\"ok\"}").doesNotContain(token, secret);
        server.verify();
    }

    @Test void conservaHtmlConCspAplicableASrcDocYNoConfundeJsonConTablero() throws Exception {
        String token = token(Map.of());
        expectToken(token, 900);
        expectEmbed(token, withSuccess(HTML, MediaType.TEXT_HTML).header("Content-Security-Policy", "img-src 'none'"));
        var response = client.fetch();
        assertThat(response.type().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(response.body()).contains("<canvas>BI</canvas>", "Content-Security-Policy", "connect-src", "img-src &#39;none&#39;")
                .doesNotContain(token, secret);
        assertThat(response.body().indexOf("Content-Security-Policy")).isLessThan(response.body().indexOf("<html"));
        server.verify();
    }

    @Test void reutilizaTokenVigente() throws Exception {
        String token = token(Map.of());
        expectToken(token, 900);
        expectEmbed(token, withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));
        expectEmbed(token, withSuccess(HTML, MediaType.TEXT_HTML));
        client.fetch(); client.fetch(); server.verify();
    }

    @Test void renuevaAntesDelVencimientoYAlRecibir401DeBi() throws Exception {
        String first = token(Map.of()); String second = token(Map.of()); String third = token(Map.of());
        expectToken(first, 90);
        expectEmbed(first, withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));
        expectToken(second, 900);
        expectEmbed(second, withStatus(HttpStatus.UNAUTHORIZED));
        expectToken(third, 900);
        expectEmbed(third, withSuccess(HTML, MediaType.TEXT_HTML));
        client.fetch();
        clock.advance(Duration.ofSeconds(70));
        assertThat(client.fetch().type().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"namespace", "version-string", "version-two", "version-decimal", "human", "audience", "issuer", "expired"})
    void rechazaTokensIncompatiblesAntesDeContactarBi(String invalid) throws Exception {
        Map<String, Object> changes = switch (invalid) {
            case "namespace" -> Map.of("namespace", "com.citypass.otro");
            case "version-string" -> Map.of("ver", "1");
            case "version-two" -> Map.of("ver", 2);
            case "version-decimal" -> Map.of("ver", 1.0);
            case "human" -> Map.of("token_use", "human");
            case "audience" -> Map.of("aud", "citypass-eda");
            case "issuer" -> Map.of("iss", "other");
            default -> Map.of("exp", Date.from(clock.instant().minusSeconds(10)));
        };
        expectToken(token(changes), 900);
        assertThatThrownBy(client::fetch).isInstanceOfSatisfying(AnalyticsException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("ANALYTICS_SERVICE_TOKEN"));
        server.verify();
    }

    @Test void verificaFirmaRs256() throws Exception {
        String jwt = token(Map.of());
        var parsed = SignedJWT.parse(jwt);
        var other = new SignedJWT(parsed.getHeader(), parsed.getJWTClaimsSet());
        other.sign(new RSASSASigner(new RSAKeyGenerator(2048).generate()));
        expectToken(other.serialize(), 900);
        assertThatThrownBy(client::fetch).isInstanceOf(AnalyticsException.class);
        server.verify();
    }

    @Test void noAceptaOtroAlgoritmoAunqueLaClaveRsaSeaCorrecta() throws Exception {
        var valid = SignedJWT.parse(token(Map.of()));
        var wrongAlgorithm = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS512).keyID("test").build(), valid.getJWTClaimsSet());
        wrongAlgorithm.sign(new RSASSASigner(key));
        expectToken(wrongAlgorithm.serialize(), 900);
        assertThatThrownBy(client::fetch).isInstanceOf(AnalyticsException.class);
        server.verify();
    }

    @Test void exigeClaimsDeVersionNamespaceYExpiracion() throws Exception {
        var changes = new java.util.HashMap<String, Object>();
        changes.put("ver", null); changes.put("namespace", null); changes.put("exp", null);
        expectToken(token(changes), 900);
        assertThatThrownBy(client::fetch).isInstanceOf(AnalyticsException.class);
        server.verify();
    }

    @ParameterizedTest @ValueSource(ints = {401, 403, 404, 503})
    void erroresDeBiNoSeConviertenEnErroresDelLoginHumano(int status) throws Exception {
        String token = token(Map.of());
        expectToken(token, 900);
        expectEmbed(token, withStatus(HttpStatus.valueOf(status)));
        if (status == 401) { expectToken(token, 900); expectEmbed(token, withStatus(HttpStatus.UNAUTHORIZED)); }
        assertThatThrownBy(client::fetch).isInstanceOfSatisfying(AnalyticsException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status >= 500 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY);
            assertThat(ex.getMessage()).doesNotContain(token, secret);
        });
        server.verify();
    }

    @Test void manejaTimeoutSinExponerDetallesRemotos() throws Exception {
        String token = token(Map.of()); expectToken(token, 900);
        expectEmbed(token, request -> { throw new ResourceAccessException("timeout " + token); });
        assertThatThrownBy(client::fetch).isInstanceOfSatisfying(AnalyticsException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(ex.getMessage()).doesNotContain(token, secret);
            assertThat(ex.getCause()).isNull();
        });
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "not-json", "null", "{\"status\":\"ready\"}"})
    void jsonIncompatibleNoSePresentaComoConexionNiHtml(String body) throws Exception {
        String token = token(Map.of()); expectToken(token, 900);
        expectEmbed(token, withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(client::fetch).isInstanceOf(AnalyticsException.class);
    }

    @Test void rechazaHtmlQueFiltraElToken() throws Exception {
        String token = token(Map.of()); expectToken(token, 900);
        expectEmbed(token, withSuccess(HTML.replace("BI", token), MediaType.TEXT_HTML));
        assertThatThrownBy(client::fetch).isInstanceOf(AnalyticsException.class);
    }

    @Test void limitaElTamanoDelDocumento() throws Exception {
        String token = token(Map.of()); expectToken(token, 900);
        expectEmbed(token, withSuccess("x".repeat(2 * 1024 * 1024 + 1), MediaType.TEXT_HTML));
        assertThatThrownBy(client::fetch).isInstanceOf(AnalyticsException.class);
    }

    @Test void rechazaTiposDeContenidoDesconocidos() throws Exception {
        String token = token(Map.of()); expectToken(token, 900);
        expectEmbed(token, withSuccess(HTML, MediaType.TEXT_PLAIN));
        assertThatThrownBy(client::fetch).isInstanceOf(AnalyticsException.class);
    }

    @Test void noExponeCredencialesRechazadasPorIdentidad() {
        server.expect(once(), requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN).body(secret));
        assertThatThrownBy(client::fetch).isInstanceOfSatisfying(AnalyticsException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(ex.getMessage()).doesNotContain(secret);
        });
    }

    @Test void unaUrlMalConfiguradaNoDaUnErrorInternoNiFiltraSuContenido() {
        var invalid = new AnalyticsEmbedClient(new AnalyticsProperties("not-a-url", TOKEN_URL, ISSUER,
                "https://identity.example/jwks", "client", secret, 2000, 5000));
        assertThatThrownBy(invalid::fetch).isInstanceOfSatisfying(AnalyticsException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test void configuracionAusenteNoImpideCrearElClienteNiAfectaElRestoDelModulo() {
        var disabled = new AnalyticsEmbedClient(new AnalyticsProperties("", "", "", "", "", "", 2000, 5000));
        assertThatThrownBy(disabled::fetch).isInstanceOfSatisfying(AnalyticsException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(AnalyticsEmbedClient.NOT_CONFIGURED));
    }

    static class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
