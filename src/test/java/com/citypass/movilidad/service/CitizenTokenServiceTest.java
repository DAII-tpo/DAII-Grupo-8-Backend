package com.citypass.movilidad.service;

import com.citypass.movilidad.config.CitizenAuthProperties;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El servicio emite tokens que solo él puede validar, y los rechaza ante cualquier desvío del
 * contrato. Incluye el arranque: una clave mal configurada tiene que matar la aplicación en modo
 * seguro en lugar de firmar con algo que nadie eligió.
 *
 * <p>Ojo con los tiempos: {@code JwtTimestampValidator} usa el reloj del sistema y no el que se
 * inyecta acá, así que el reloj de prueba arranca en el instante real. Para probar el vencimiento
 * hay que emitir con el reloj atrasado, no adelantarlo después.
 */
class CitizenTokenServiceTest {

    private static final String SECRETO = secretoDe(32, (byte) 7);
    private static final String OTRO_SECRETO = secretoDe(32, (byte) 9);
    private static final String ISSUER = "https://movilidad.citypass.local";
    private static final String AUDIENCE = "citypass-movilidad-api";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final MutableClock clock = MutableClock.real();

    private static String secretoDe(int bytes, byte relleno) {
        byte[] crudo = new byte[bytes];
        Arrays.fill(crudo, relleno);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(crudo);
    }

    private static CitizenAuthProperties propiedades(String issuer, String audience, String secreto) {
        return new CitizenAuthProperties(issuer, audience, secreto, TTL,
                Duration.ofDays(30), 5, Duration.ofMinutes(15), Duration.ofMinutes(10));
    }

    private CitizenTokenService servicio(String secreto) {
        return new CitizenTokenService(propiedades(ISSUER, AUDIENCE, secreto), clock, false);
    }

    private static User ciudadano(long id) {
        return User.builder().id(id).build();
    }

    @Test
    void emiteUnTokenQueSuPropioDecoderAcepta() {
        CitizenTokenService servicio = servicio(SECRETO);

        Jwt jwt = servicio.decoder().decode(servicio.issueAccessToken(ciudadano(42L)));

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getIssuer()).hasToString(ISSUER);
        assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
        assertThat(jwt.getClaimAsString("token_use")).isEqualTo(CitizenTokenService.TOKEN_USE);
        assertThat(jwt.getExpiresAt()).isEqualTo(clock.instant().plus(TTL));
    }

    /** No lleva rol: la autorización la decide la authority, no un snapshot dentro del token. */
    @Test
    void noLlevaClaimDeRol() {
        CitizenTokenService servicio = servicio(SECRETO);
        Jwt jwt = servicio.decoder().decode(servicio.issueAccessToken(ciudadano(42L)));
        assertThat(jwt.getClaims()).doesNotContainKeys("role", "roles", "authorities");
    }

    @Test
    void rechazaUnTokenFirmadoConOtraClave() {
        String ajeno = servicio(OTRO_SECRETO).issueAccessToken(ciudadano(42L));

        JwtDecoder decoder = servicio(SECRETO).decoder();
        assertThatThrownBy(() -> decoder.decode(ajeno))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rechazaUnTokenVencido() {
        MutableClock atrasado = MutableClock.real();
        atrasado.advance(Duration.ofHours(-2));
        CitizenTokenService servicio = new CitizenTokenService(
                propiedades(ISSUER, AUDIENCE, SECRETO), atrasado, false);

        String vencidoHaceRato = servicio.issueAccessToken(ciudadano(42L));

        JwtDecoder decoder = servicio.decoder();
        assertThatThrownBy(() -> decoder.decode(vencidoHaceRato))
                .isInstanceOf(JwtException.class);
    }

    /**
     * Un token del IdP federado no puede pasar por acá ni al revés: tienen issuer, clave y
     * token_use distintos. Se prueba el caso más favorable para un atacante, otro issuer sobre
     * nuestra propia clave.
     */
    @Test
    void rechazaUnTokenConOtroIssuer() {
        String token = new CitizenTokenService(
                propiedades("https://idp.citypass.local", AUDIENCE, SECRETO), clock, false)
                .issueAccessToken(ciudadano(42L));

        JwtDecoder decoder = servicio(SECRETO).decoder();
        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rechazaUnAudienceAjeno() {
        String token = new CitizenTokenService(
                propiedades(ISSUER, "citypass-reclamos-api", SECRETO), clock, false)
                .issueAccessToken(ciudadano(42L));

        JwtDecoder decoder = servicio(SECRETO).decoder();
        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void exponeElTtlConfigurado() {
        assertThat(servicio(SECRETO).accessTokenTtl()).isEqualTo(TTL);
    }

    // --- Arranque y clave ---

    @Test
    void fallaAlArrancarConUnSecretoDemasiadoCorto() {
        String corto = secretoDe(31, (byte) 7);
        assertThatThrownBy(() -> servicio(corto))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("31")
                .hasMessageContaining("32");
    }

    @Test
    void fallaAlArrancarConUnSecretoQueNoEsBase64url() {
        assertThatThrownBy(() -> servicio("no es base64url!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64url");
    }

    /** En modo seguro, arrancar sin clave elegida es un error de configuración, no un default. */
    @Test
    void fallaAlArrancarSinSecretoEnModoSeguro() {
        assertThatThrownBy(() -> servicio(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.local.enabled");
    }

    /**
     * En modo local arranca con una clave aleatoria: es lo que evita tener un secreto de firma
     * commiteado en el repo, al precio de que los tokens no sobrevivan a un reinicio.
     */
    @Test
    void enModoLocalSinSecretoGeneraUnaClaveAleatoria() {
        CitizenTokenService primero = new CitizenTokenService(
                propiedades(ISSUER, AUDIENCE, null), clock, true);
        CitizenTokenService segundo = new CitizenTokenService(
                propiedades(ISSUER, AUDIENCE, null), clock, true);

        String token = primero.issueAccessToken(ciudadano(42L));

        assertThat(primero.decoder().decode(token).getSubject()).isEqualTo("42");
        // Cada arranque genera la suya: el token del primero no vale para el segundo.
        JwtDecoder decoder = segundo.decoder();
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

}
