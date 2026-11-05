package com.citypass.movilidad.config;

import com.citypass.movilidad.model.User;
import com.citypass.movilidad.repository.RoleRepository;
import com.citypass.movilidad.repository.UserRepository;
import com.citypass.movilidad.support.FederatedSecurityTestConfig;
import com.citypass.movilidad.support.TestJwtFactory;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * La cadena de seguridad completa en modo seguro, con tokens firmados de verdad contra MySQL
 * real: los cinco rechazos del spec, el 403 por grupo LDAP y el alta just-in-time.
 *
 * <p>Es el único test que cubre el 403 de admin: {@code @PreAuthorize} es inerte en modo local
 * (ver {@link MethodSecurityConfig}), así que nada que corra ahí lo verifica.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = "security.local.enabled=false")
@Import(FederatedSecurityTestConfig.class)
class FederatedAuthIntegrationTest {

    /** Endpoint de personal: exige el grupo LDAP de administradores. */
    private static final String ADMIN_ENDPOINT = "/api/v1/maintenance";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("movilidad")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configuracionDinamica(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private final TestJwtFactory tokens = FederatedSecurityTestConfig.TOKENS;

    private JWTClaimsSet.Builder claims(String subject, List<String> groups) {
        return tokens.validClaims(subject, groups);
    }

    private void esperaUnauthorized(String token) throws Exception {
        mockMvc.perform(get(ADMIN_ENDPOINT).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // --- Las cinco validaciones, cada una como rechazo 401 ---

    @Test
    void rechazaSinToken() throws Exception {
        mockMvc.perform(get(ADMIN_ENDPOINT)).andExpect(status().isUnauthorized());
    }

    @Test
    void rechazaUnTokenConFirmaInvalida() throws Exception {
        esperaUnauthorized(tokens.signWithForeignKey(claims("U000042", List.of("admin")).build()));
    }

    @Test
    void rechazaUnTokenDeOtroIssuer() throws Exception {
        esperaUnauthorized(tokens.sign(claims("U000042", List.of("admin"))
                .issuer("https://idp.atacante.local").build()));
    }

    @Test
    void rechazaUnTokenVencido() throws Exception {
        Instant pasado = Instant.now().minus(1, ChronoUnit.HOURS);
        esperaUnauthorized(tokens.sign(claims("U000042", List.of("admin"))
                .issueTime(Date.from(pasado))
                .expirationTime(Date.from(pasado.plus(15, ChronoUnit.MINUTES)))
                .build()));
    }

    @Test
    void rechazaUnTokenDeOtroModulo() throws Exception {
        esperaUnauthorized(tokens.sign(claims("U000042", List.of("admin"))
                .audience(List.of("citypass-reclamos-api")).build()));
    }

    @Test
    void rechazaUnTokenDeServicio() throws Exception {
        esperaUnauthorized(tokens.sign(claims("U000042", List.of("admin"))
                .claim("token_use", "service").build()));
    }

    // --- Autorización por grupo LDAP ---

    @Test
    void permiteElEndpointDeAdminAlGrupoAdmin() throws Exception {
        mockMvc.perform(get(ADMIN_ENDPOINT)
                        .header("Authorization", "Bearer " + tokens.signedToken("U000100", List.of("admin"))))
                .andExpect(status().isOk());
    }

    @Test
    void rechazaElEndpointDeAdminAUnaPersonaSinEseGrupo() throws Exception {
        mockMvc.perform(get(ADMIN_ENDPOINT)
                        .header("Authorization", "Bearer " + tokens.signedToken("U000101", List.of("operadores"))))
                .andExpect(status().isForbidden());
    }

    /** El spec avisa que groups puede venir vacío: el token vale, pero no es admin. */
    @Test
    void rechazaElEndpointDeAdminAUnTokenConGruposVacios() throws Exception {
        mockMvc.perform(get(ADMIN_ENDPOINT)
                        .header("Authorization", "Bearer " + tokens.signedToken("U000102", List.of())))
                .andExpect(status().isForbidden());
    }

    // --- Identidad ---

    @Test
    void daDeAltaUnaSolaFilaPorPersonaYLaReusa() throws Exception {
        String sub = "U000200";
        assertThat(userRepository.findByExternalUserId(sub)).isEmpty();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get(ADMIN_ENDPOINT)
                            .header("Authorization", "Bearer " + tokens.signedToken(sub, List.of("admin"))))
                    .andExpect(status().isOk());
        }

        User alta = userRepository.findByExternalUserId(sub).orElseThrow();
        assertThat(alta.getExternalUserId()).isEqualTo(sub);
        // El access token no trae email ni apellido: salen del GET /me del IdP, que acá responde
        // el doble de FederatedSecurityTestConfig con datos derivados del sub.
        assertThat(alta.getEmail()).isEqualTo("u000200@citypass.local");
        assertThat(alta.getFirstName()).isEqualTo("Persona");
        assertThat(alta.getLastName()).isEqualTo(sub);
        // Role es LAZY y acá no hay sesión abierta (open-in-view=false): el id lo da el
        // proxy sin inicializarlo, así que se compara contra el de la fila ADMIN.
        assertThat(alta.getRole().getId())
                .isEqualTo(roleRepository.findByName("ADMIN").orElseThrow().getId());
        assertThat(userRepository.findAll().stream()
                .filter(user -> sub.equals(user.getExternalUserId()))).hasSize(1);
    }

    /** Las lecturas públicas siguen abiertas: no hace falta token para la disponibilidad. */
    @Test
    void laDisponibilidadSigueSiendoPublica() throws Exception {
        mockMvc.perform(get("/api/v1/stations/availability")).andExpect(status().isOk());
    }
}
