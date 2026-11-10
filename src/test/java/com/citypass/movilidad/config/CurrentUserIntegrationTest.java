package com.citypass.movilidad.config;

import com.citypass.movilidad.repository.UserRepository;
import com.citypass.movilidad.support.FederatedSecurityTestConfig;
import com.citypass.movilidad.support.TestJwtFactory;
import com.citypass.movilidad.support.TestSecrets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/users/me} en modo seguro, con tokens firmados de verdad contra MySQL real:
 * un caso por cada tipo de usuario.
 *
 * <p>Junto con el rol que informa, se verifica que coincide con la autorización real: un empleado
 * que el endpoint describe como {@code USER} recibe 403 al crear una estación. Si el front y el
 * back calcularan el rol por separado, esa coincidencia es lo primero que se rompería.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "security.local.enabled=false",
        TestSecrets.CITIZEN_AUTH_SECRET_PROPERTY})
@Import(FederatedSecurityTestConfig.class)
class CurrentUserIntegrationTest {

    private static final String ME = "/api/v1/users/me";

    private static final String ESTACION_VALIDA =
            "{\"name\":\"Estación de prueba de /users/me\",\"latitude\":-34.6037,"
                    + "\"longitude\":-58.3816,\"capacity\":10}";

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
        registry.add("movilidad.emergency.deadline-check-interval", () -> "1h");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    private final TestJwtFactory tokens = FederatedSecurityTestConfig.TOKENS;

    private String federado(String subject, List<String> groups) {
        return "Bearer " + tokens.signedToken(subject, groups);
    }

    // --- Personal municipal (IdP del Grupo 2) ---

    @Test
    void elGrupoAdminEsStaffConRolAdmin() throws Exception {
        mockMvc.perform(get(ME).header("Authorization", federado("U000700", List.of("admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("STAFF"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.groups", contains("admin")));

        // El primer request da de alta la fila, y el id informado es el de esa fila.
        long id = userRepository.findByExternalUserId("U000700").orElseThrow().getId();
        mockMvc.perform(get(ME).header("Authorization", federado("U000700", List.of("admin"))))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.email").value("u000700@citypass.local"))
                .andExpect(jsonPath("$.lastName").value("U000700"));
    }

    @Test
    void elGrupoDelegadosTambienEsAdmin() throws Exception {
        mockMvc.perform(get(ME).header("Authorization", federado("U000701", List.of("delegados"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("STAFF"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    /** El grupo se compara en minúsculas y con trim, igual que en las authorities. */
    @Test
    void normalizaElGrupoAntesDeResolverElRol() throws Exception {
        mockMvc.perform(get(ME).header("Authorization", federado("U000702", List.of("Admin "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.groups", contains("admin")));
    }

    /** Un rol USER informado coincide con la autorización real: crear una estación da 403. */
    @Test
    void elGrupoOperadoresEsUserYNoPuedeAdministrar() throws Exception {
        String token = federado("U000703", List.of("operadores"));

        mockMvc.perform(get(ME).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("STAFF"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.groups", contains("operadores")));

        mockMvc.perform(post("/api/v1/stations").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(ESTACION_VALIDA))
                .andExpect(status().isForbidden());
    }

    @Test
    void sinGruposEsUser() throws Exception {
        String token = federado("U000704", List.of());

        mockMvc.perform(get(ME).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("STAFF"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.groups", empty()));

        mockMvc.perform(post("/api/v1/stations").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(ESTACION_VALIDA))
                .andExpect(status().isForbidden());
    }

    // --- Ciudadanos (login propio) ---

    @Test
    void elCiudadanoEsCitizenConSuId() throws Exception {
        String email = "ciudadano-" + UUID.randomUUID() + "@example.com";
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TestSecrets.CITIZEN_PASSWORD
                                + "\",\"firstName\":\"Ana\",\"lastName\":\"Pérez\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode registro = objectMapper.readTree(body);
        String token = "Bearer " + registro.get("accessToken").asText();
        long id = userRepository.findByEmail(email).orElseThrow().getId();

        mockMvc.perform(get(ME).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.type").value("CITIZEN"))
                .andExpect(jsonPath("$.role").value("CITIZEN"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.firstName").value("Ana"))
                .andExpect(jsonPath("$.groups", empty()));
        assertThat(registro.get("userId").asLong()).isEqualTo(id);

        mockMvc.perform(post("/api/v1/stations").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(ESTACION_VALIDA))
                .andExpect(status().isForbidden());
    }

    // --- Sin identidad ---

    @Test
    void sinTokenDa401() throws Exception {
        mockMvc.perform(get(ME)).andExpect(status().isUnauthorized());
    }

    @Test
    void conUnBearerBasuraDa401() throws Exception {
        mockMvc.perform(get(ME).header("Authorization", "Bearer no.es.un.token"))
                .andExpect(status().isUnauthorized());
    }

    /** Un bearer inválido da 401 incluso en un endpoint público: el filtro corre antes del permitAll. */
    @Test
    void unBearerBasuraDa401InclusoEnUnEndpointPublico() throws Exception {
        mockMvc.perform(get("/api/v1/stations/availability")
                        .header("Authorization", "Bearer no.es.un.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sinTokenLosPublicosSiguenAbiertos() throws Exception {
        mockMvc.perform(get("/api/v1/stations/availability")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
