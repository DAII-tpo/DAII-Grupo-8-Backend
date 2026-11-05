package com.citypass.movilidad.service;

import com.citypass.movilidad.client.FederatedUserInfo;
import com.citypass.movilidad.client.FederatedUserInfoClient;
import com.citypass.movilidad.config.AuthProperties;
import com.citypass.movilidad.config.CitizenAuthProperties;
import com.citypass.movilidad.config.FederatedRoleResolver;
import com.citypass.movilidad.exception.UnauthorizedException;
import com.citypass.movilidad.exception.ForbiddenOperationException;
import com.citypass.movilidad.model.Role;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.UserStatus;
import com.citypass.movilidad.repository.RoleRepository;
import com.citypass.movilidad.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Resolución de la identidad federada a la fila local, con alta just-in-time. */
class CurrentUserServiceTest {

    private static final String SUB = "U000042";
    private static final String CITIZEN_ISSUER = "https://movilidad.citypass.local";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final AuthProperties authProperties = new AuthProperties(
            "https://api.citypass-login.pp.ua/.well-known/jwks.json",
            "https://idp.citypass.local", "citypass-movilidad-api", "human",
            Map.of("admin", "ADMIN", "operadores", "USER"),
            "USER", List.of("ADMIN", "USER"),
            List.of(), List.of(), 1L);
    private final FederatedUserInfoClient userInfoClient = mock(FederatedUserInfoClient.class);
    private final FederatedUserProvisioner provisioner =
            new FederatedUserProvisioner(userRepository, roleRepository, authProperties);
    private final CitizenAuthProperties citizenProperties = new CitizenAuthProperties(
            CITIZEN_ISSUER, "citypass-movilidad-api", null, Duration.ofMinutes(30),
            Duration.ofDays(30), 5, Duration.ofMinutes(15), Duration.ofMinutes(10));
    private final CurrentUserService service = servicio(true);
    private final CurrentUserService enModoSeguro = servicio(false);

    private CurrentUserService servicio(boolean localMode) {
        return new CurrentUserService(userRepository, provisioner,
                new FederatedRoleResolver(authProperties), userInfoClient, authProperties,
                citizenProperties, localMode);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    private static Role role(String name) {
        Role role = new Role();
        role.setName(name);
        return role;
    }

    private static User user(Long id, String roleName, UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setExternalUserId(SUB);
        user.setRole(role(roleName));
        user.setStatus(status);
        return user;
    }

    private void autenticar(String subject, List<String> groups) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256")
                .subject(subject)
                .claim("preferred_username", "jperez")
                .claim("groups", groups)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));
    }

    /** Token nuestro: lo distingue el iss, y el sub es el id local. */
    private void autenticarCiudadano(String subject) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256")
                .issuer(CITIZEN_ISSUER)
                .subject(subject)
                .claim("token_use", "citizen")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));
    }

    @Test
    void reusaLaFilaExistenteCuandoElRolYaCoincide() {
        autenticar(SUB, List.of("admin"));
        when(userRepository.findByExternalUserId(SUB))
                .thenReturn(Optional.of(user(7L, "ADMIN", UserStatus.ACTIVE)));

        assertThat(service.currentUserId()).isEqualTo(7L);
        verify(userRepository, never()).save(any());
    }

    /** Un cambio de grupos en el panel del delegado se baja a la fila local al próximo token. */
    @Test
    void sincronizaElRolCuandoCambiaronLosGruposDelToken() {
        autenticar(SUB, List.of("operadores"));
        User existente = user(7L, "ADMIN", UserStatus.ACTIVE);
        when(userRepository.findByExternalUserId(SUB)).thenReturn(Optional.of(existente));
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(role("USER")));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.requireCurrentUser();
        // Pasó de admin a personal técnico: la degradación también tiene que bajarse.
        assertThat(existente.getRole().getName()).isEqualTo("USER");
    }

    @Test
    void daDeAltaLaFilaEnElPrimerRequestDeLaPersona() {
        autenticar(SUB, List.of("admin"));
        when(userRepository.findByExternalUserId(SUB)).thenReturn(Optional.empty());
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(role("ADMIN")));
        when(userRepository.save(any())).thenAnswer(i -> {
            User creado = i.getArgument(0);
            creado.setId(99L);
            return creado;
        });

        User alta = service.requireCurrentUser();
        assertThat(alta.getId()).isEqualTo(99L);
        assertThat(alta.getExternalUserId()).isEqualTo(SUB);
        // Alta degradada: el IdP no contestó el perfil, así que el email queda nulo en lugar de
        // inventarse un valor y el nombre cae al preferred_username del token.
        assertThat(alta.getEmail()).isNull();
        assertThat(alta.getFirstName()).isEqualTo("jperez");
        assertThat(alta.getRole().getName()).isEqualTo("ADMIN");
    }

    /** El alta normal toma del IdP el email y el apellido, que el access token no trae. */
    @Test
    void daDeAltaConElPerfilQueDevuelveElIdp() {
        autenticar(SUB, List.of("admin"));
        when(userRepository.findByExternalUserId(SUB)).thenReturn(Optional.empty());
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(role("ADMIN")));
        when(userInfoClient.fetch(any(), eq(SUB))).thenReturn(Optional.of(
                new FederatedUserInfo("ana.perez@citypass.local", "Ana", "Pérez")));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        User alta = service.requireCurrentUser();
        assertThat(alta.getEmail()).isEqualTo("ana.perez@citypass.local");
        assertThat(alta.getFirstName()).isEqualTo("Ana");
        assertThat(alta.getLastName()).isEqualTo("Pérez");
    }

    /**
     * Una fila creada mientras el IdP estaba caído se completa en un request posterior. Importa
     * porque el contrato de eventos del bus exige email y apellido.
     */
    @Test
    void completaElPerfilDeUnaFilaQueQuedoIncompleta() {
        autenticar(SUB, List.of("admin"));
        User incompleta = user(7L, "ADMIN", UserStatus.ACTIVE);
        incompleta.setFirstName("jperez");
        when(userRepository.findByExternalUserId(SUB)).thenReturn(Optional.of(incompleta));
        when(userInfoClient.fetch(any(), eq(SUB))).thenReturn(Optional.of(
                new FederatedUserInfo("ana.perez@citypass.local", "Ana", "Pérez")));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.requireCurrentUser();
        assertThat(incompleta.getEmail()).isEqualTo("ana.perez@citypass.local");
        assertThat(incompleta.getLastName()).isEqualTo("Pérez");
        // El nombre real del directorio reemplaza al preferred_username del alta degradada.
        assertThat(incompleta.getFirstName()).isEqualTo("Ana");
    }

    /** Con la fila ya completa no se consulta al IdP: una llamada HTTP por request es inaceptable. */
    @Test
    void noConsultaAlIdpSiLaFilaYaEstaCompleta() {
        autenticar(SUB, List.of("admin"));
        User completa = user(7L, "ADMIN", UserStatus.ACTIVE);
        completa.setEmail("ana.perez@citypass.local");
        completa.setLastName("Pérez");
        when(userRepository.findByExternalUserId(SUB)).thenReturn(Optional.of(completa));

        assertThat(service.currentUserId()).isEqualTo(7L);
        verify(userInfoClient, never()).fetch(any(), any());
        verify(userRepository, never()).save(any());
    }

    /** Dos requests simultáneos del mismo sub: el índice único hace fallar al segundo insert. */
    @Test
    void anteUnAltaSimultaneaReleeLaFilaGanadora() {
        autenticar(SUB, List.of());
        User ganadora = user(5L, "USER", UserStatus.ACTIVE);
        when(userRepository.findByExternalUserId(SUB))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(ganadora));
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(role("USER")));
        when(userRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("uk_users_external_user_id"));

        assertThat(service.currentUserId()).isEqualTo(5L);
    }

    /** Si el insert falla y tampoco aparece la fila, el error original tiene que propagarse. */
    @Test
    void propagaElErrorSiElAltaFallaYNoHayFilaGanadora() {
        autenticar(SUB, List.of());
        when(userRepository.findByExternalUserId(SUB)).thenReturn(Optional.empty());
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(role("USER")));
        when(userRepository.save(any())).thenThrow(new DataIntegrityViolationException("otra cosa"));

        assertThatThrownBy(service::requireCurrentUser)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rechazaAUnUsuarioBloqueadoAunqueElTokenSeaValido() {
        autenticar(SUB, List.of("admin"));
        when(userRepository.findByExternalUserId(SUB))
                .thenReturn(Optional.of(user(7L, "ADMIN", UserStatus.BLOCKED)));

        assertThatThrownBy(service::requireCurrentUser)
                .isInstanceOf(ForbiddenOperationException.class)
                // El mensaje identifica por id local y no por external_user_id: un ciudadano lo
                // tiene en NULL, y el mensaje habría dicho "no está habilitado: null".
                .hasMessageContaining("7");
    }

    @Test
    void fallaSiElRolMapeadoNoExisteNiElRolPorDefecto() {
        autenticar(SUB, List.of("admin"));
        when(userRepository.findByExternalUserId(SUB)).thenReturn(Optional.empty());
        when(roleRepository.findByName(any())).thenReturn(Optional.empty());

        assertThatThrownBy(service::requireCurrentUser)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("roles");
    }

    /** Si el rol mapeado no está en la base se cae al rol por defecto, que sí está. */
    @Test
    void caeAlRolPorDefectoSiElRolMapeadoNoEstaEnLaBase() {
        autenticar(SUB, List.of("admin"));
        when(userRepository.findByExternalUserId(SUB)).thenReturn(Optional.empty());
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.empty());
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(role("USER")));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.requireCurrentUser().getRole().getName()).isEqualTo("USER");
    }

    @Test
    void rechazaUnTokenSinElClaimSub() {
        autenticar(null, List.of());
        assertThatThrownBy(service::requireCurrentUser)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sub");
    }

    /** En modo local no hay token: se usa el usuario configurado. */
    @Test
    void sinTokenUsaElUsuarioLocalConfigurado() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "ADMIN", UserStatus.ACTIVE)));
        assertThat(service.currentUserId()).isEqualTo(1L);
    }

    @Test
    void sinTokenFallaSiElUsuarioLocalNoExiste() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(service::requireCurrentUser)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("usuario local");
    }

    /** Una autenticación que no es de JWT tampoco aporta identidad federada. */
    @Test
    void unaAutenticacionQueNoEsJwtCaeAlUsuarioLocal() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alguien", "credenciales"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "USER", UserStatus.ACTIVE)));
        assertThat(service.currentUserId()).isEqualTo(1L);
    }

    // --- Rama de ciudadano ---

    /** El iss de nuestro token manda la resolución por id local, no por external_user_id. */
    @Test
    void resuelveAlCiudadanoPorElIssDeNuestroToken() {
        autenticarCiudadano("7");
        User ciudadano = user(7L, "USER", UserStatus.ACTIVE);
        ciudadano.setExternalUserId(null);
        when(userRepository.findById(7L)).thenReturn(Optional.of(ciudadano));

        assertThat(service.currentUserId()).isEqualTo(7L);
        // No da de alta ni reconcilia rol: la cuenta ya existe y su rol no sale de grupos LDAP.
        verify(userRepository, never()).findByExternalUserId(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void rechazaUnCiudadanoQueYaNoExiste() {
        autenticarCiudadano("7");
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(service::requireCurrentUser)
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rechazaUnSubQueNoEsUnIdLocal() {
        autenticarCiudadano("U000042");

        assertThatThrownBy(service::requireCurrentUser)
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("identidad local");
    }

    @Test
    void rechazaUnCiudadanoBloqueado() {
        autenticarCiudadano("7");
        when(userRepository.findById(7L))
                .thenReturn(Optional.of(user(7L, "USER", UserStatus.BLOCKED)));

        assertThatThrownBy(service::requireCurrentUser)
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("7");
    }

    /**
     * Este es el bug que el paso existe para cerrar: en modo seguro, un request sin identidad no
     * puede imputarle sus acciones al usuario demo.
     */
    @Test
    void enModoSeguroSinTokenNoCaeAlUsuarioDemo() {
        assertThatThrownBy(enModoSeguro::requireCurrentUser)
                .isInstanceOf(UnauthorizedException.class);

        verify(userRepository, never()).findById(any());
    }

    @Test
    void avisaAlArrancarSiUnRolMapeadoNoEstaEnLaBase() {
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.empty());
        when(roleRepository.findByName("USER")).thenReturn(Optional.of(role("USER")));

        provisioner.warnAboutMissingRoles();
        verify(roleRepository).findByName("ADMIN");
    }
}
