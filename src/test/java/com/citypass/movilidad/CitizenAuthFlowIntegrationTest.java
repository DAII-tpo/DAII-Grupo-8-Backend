package com.citypass.movilidad;

import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.repository.BikeRepository;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.TripRepository;
import com.citypass.movilidad.repository.UserRepository;
import com.citypass.movilidad.support.FederatedSecurityTestConfig;
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

import java.math.BigDecimal;
import java.util.UUID;

import static com.citypass.movilidad.support.FederatedSecurityTestConfig.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registro, ingreso y uso de la API por un ciudadano, en modo seguro y contra MySQL real.
 *
 * <p>El test que da sentido al paso entero es {@code elViajeQuedaImputadoAlCiudadano}: si la
 * identidad se resolviera mal, los viajes de todas las personas caerían en el usuario demo, sin
 * ningún error visible.
 *
 * <p>Y {@code losDosEmisoresFuncionanEnElMismoArranque} es el que prueba la convivencia de verdad:
 * un token del IdP y uno nuestro, en el mismo contexto.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "security.local.enabled=false",
        TestSecrets.CITIZEN_AUTH_SECRET_PROPERTY})
@Import(FederatedSecurityTestConfig.class)
class CitizenAuthFlowIntegrationTest {


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
        // El job de emergencias corre cada 5 s por defecto y, al apagarse el contenedor, deja
        // errores de transacción en el log. No rompe nada, pero es ruido inútil.
        registry.add("movilidad.emergency.deadline-check-interval", () -> "1h");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StationRepository stationRepository;

    @Autowired
    private BikeRepository bikeRepository;

    @Autowired
    private TripRepository tripRepository;

    private static String emailNuevo() {
        return "ciudadano-" + UUID.randomUUID() + "@example.com";
    }

    /** Registra una cuenta y devuelve el cuerpo con el par de tokens. */
    private JsonNode registrar(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TestSecrets.CITIZEN_PASSWORD
                                + "\",\"firstName\":\"Ana\",\"lastName\":\"Pérez\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode login(String email, String password, int statusEsperado) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().is(statusEsperado))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void registraIngresaYDevuelveUnParDeTokens() throws Exception {
        String email = emailNuevo();

        JsonNode registro = registrar(email);
        assertThat(registro.get("accessToken").asText()).isNotBlank();
        assertThat(registro.get("refreshToken").asText()).isNotBlank();
        assertThat(registro.get("role").asText()).isEqualTo("USER");

        // La fila del ciudadano no tiene identidad en el IdP: eso lo distingue de un empleado.
        var fila = userRepository.findByEmail(email).orElseThrow();
        assertThat(fila.getExternalUserId()).isNull();
        assertThat(fila.getPasswordHash()).isNotBlank().isNotEqualTo(TestSecrets.CITIZEN_PASSWORD);

        JsonNode ingreso = login(email, TestSecrets.CITIZEN_PASSWORD, 200);
        assertThat(ingreso.get("userId").asLong()).isEqualTo(fila.getId());
    }

    @Test
    void rechazaElSegundoRegistroDelMismoEmail() throws Exception {
        String email = emailNuevo();
        registrar(email);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TestSecrets.CITIZEN_PASSWORD
                                + "\",\"firstName\":\"Ana\",\"lastName\":\"Pérez\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    /**
     * El test central del paso: el viaje tiene que quedar imputado al ciudadano y no al usuario
     * demo de la V3, que es a quien caían las acciones cuando la identidad no se resolvía.
     */
    @Test
    void elViajeQuedaImputadoAlCiudadano() throws Exception {
        String email = emailNuevo();
        JsonNode registro = registrar(email);
        String token = registro.get("accessToken").asText();
        long ciudadanoId = registro.get("userId").asLong();

        Station estacion = estacion("Estación ciudadano " + UUID.randomUUID(), 10);
        Bike bici = bici("CIT-" + UUID.randomUUID().toString().substring(0, 8), estacion);

        String viaje = mockMvc.perform(post("/api/v1/trips")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bikeId\":" + bici.getId() + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long viajeId = objectMapper.readTree(viaje).get("id").asLong();
        Trip guardado = tripRepository.findById(viajeId).orElseThrow();

        assertThat(guardado.getUser().getId()).isEqualTo(ciudadanoId);
        assertThat(ciudadanoId).isNotEqualTo(1L);
    }

    /** Un ciudadano recibe ROLE_CITIZEN y nada más, así que la administración le queda cerrada. */
    @Test
    void elCiudadanoNoLlegaALosEndpointsDeAdministracion() throws Exception {
        String token = registrar(emailNuevo()).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/maintenance").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /** La convivencia de los dos emisores, en el mismo contexto. */
    @Test
    void losDosEmisoresFuncionanEnElMismoArranque() throws Exception {
        String deCiudadano = registrar(emailNuevo()).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/trips/active").header("Authorization", "Bearer " + deCiudadano))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/maintenance").with(bearer("U000500", "admin")))
                .andExpect(status().isOk());
    }

    @Test
    void rechazaUnTokenConUnEmisorDesconocido() throws Exception {
        mockMvc.perform(get("/api/v1/trips/active")
                        .header("Authorization", "Bearer " + "no.es.un.token"))
                .andExpect(status().isUnauthorized());
    }

    // --- Refresh ---

    @Test
    void elRefreshRotaYElViejoDejaDeValer() throws Exception {
        String viejo = registrar(emailNuevo()).get("refreshToken").asText();

        String body = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + viejo + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode nuevo = objectMapper.readTree(body);

        assertThat(nuevo.get("refreshToken").asText()).isNotBlank().isNotEqualTo(viejo);

        // Reusar el gastado corta la familia: el nuevo también deja de valer.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + viejo + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + nuevo.get("refreshToken").asText() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --- Intentos fallidos ---

    @Test
    void cincoFallosBloqueanLaCuentaAunConLaContrasenaCorrecta() throws Exception {
        String email = emailNuevo();
        registrar(email);

        for (int i = 0; i < 5; i++) {
            login(email, TestSecrets.WRONG_PASSWORD, 401);
        }

        // Con la contraseña correcta, el código cambia: es el bloqueo y no las credenciales.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + TestSecrets.CITIZEN_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    /** El contador se persiste pese al 401: con el rollback por defecto nunca llegaría a 5. */
    @Test
    void elContadorDeIntentosSePersisteEntreRequests() throws Exception {
        String email = emailNuevo();
        registrar(email);

        login(email, TestSecrets.WRONG_PASSWORD, 401);
        login(email, TestSecrets.WRONG_PASSWORD, 401);

        assertThat(userRepository.findByEmail(email).orElseThrow().getFailedLoginAttempts())
                .isEqualTo(2);
    }

    @Test
    void unEmailInexistenteYUnaContrasenaIncorrectaDevuelvenLoMismo() throws Exception {
        String email = emailNuevo();
        registrar(email);

        JsonNode inexistente = login(emailNuevo(), TestSecrets.CITIZEN_PASSWORD, 401);
        JsonNode incorrecta = login(email, TestSecrets.WRONG_PASSWORD, 401);

        assertThat(inexistente.get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(incorrecta.get("code").asText())
                .isEqualTo(inexistente.get("code").asText());
        assertThat(incorrecta.get("message").asText())
                .isEqualTo(inexistente.get("message").asText());
    }

    /** Los endpoints de auth tienen que ser públicos: son la puerta de entrada. */
    @Test
    void losEndpointsDeAuthNoExigenToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + emailNuevo() + "\",\"password\":\"" + TestSecrets.CITIZEN_PASSWORD + "\"}"))
                // 401 de credenciales, no de token ausente: el request llegó al controller.
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    private Station estacion(String nombre, int capacidad) {
        Station station = new Station();
        station.setName(nombre);
        station.setLatitude(new BigDecimal("-34.6083000"));
        station.setLongitude(new BigDecimal("-58.3712000"));
        station.setCapacity(capacidad);
        station.setSource(StationSource.MANUAL);
        return stationRepository.save(station);
    }

    private Bike bici(String codigo, Station estacion) {
        Bike bike = new Bike();
        bike.setCode(codigo);
        bike.setStation(estacion);
        bike.setStatus(BikeStatus.AVAILABLE);
        return bikeRepository.save(bike);
    }
}
