package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.AuthTokenResponse;
import com.citypass.movilidad.dto.CitizenLoginRequest;
import com.citypass.movilidad.dto.CitizenRegisterRequest;
import com.citypass.movilidad.dto.TokenRefreshRequest;
import com.citypass.movilidad.exception.EmailAlreadyRegisteredException;
import com.citypass.movilidad.exception.ForbiddenOperationException;
import com.citypass.movilidad.exception.UnauthorizedException;
import com.citypass.movilidad.model.Role;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.UserStatus;
import com.citypass.movilidad.repository.RoleRepository;
import com.citypass.movilidad.repository.UserRepository;
import com.citypass.movilidad.support.TestSecrets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Registro e ingreso. El eje de los tests es que ningún camino de fallo del login distinga casos:
 * cuenta inexistente, contraseña incorrecta y cuenta sin contraseña local devuelven lo mismo.
 */
class CitizenAuthServiceTest {

    private static final String EMAIL = "ana.perez@example.com";

    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final CitizenTokenService tokens = mock(CitizenTokenService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final LoginAttemptPolicy attempts = mock(LoginAttemptPolicy.class);

    private CitizenAuthService service;

    @BeforeEach
    void setUp() {
        when(tokens.issueAccessToken(any())).thenReturn("el-access-token");
        when(tokens.accessTokenTtl()).thenReturn(Duration.ofMinutes(30));
        when(refreshTokens.issueForNewSession(any())).thenReturn("el-refresh-token");
        service = new CitizenAuthService(users, roles, encoder, tokens, refreshTokens, attempts);
    }

    private static Role rol(String nombre) {
        Role role = new Role();
        role.setName(nombre);
        return role;
    }

    private static User ciudadano(UserStatus status, String passwordHash) {
        return User.builder()
                .id(7L)
                .email(EMAIL)
                .passwordHash(passwordHash)
                .firstName("Ana")
                .lastName("Pérez")
                .role(rol("USER"))
                .status(status)
                .build();
    }

    // --- Registro ---

    @Test
    void registraNormalizandoElEmailYGuardandoSoloElHash() {
        when(users.existsByEmail(EMAIL)).thenReturn(false);
        when(roles.findByName("USER")).thenReturn(Optional.of(rol("USER")));
        when(encoder.encode(TestSecrets.CITIZEN_PASSWORD)).thenReturn(TestSecrets.FAKE_BCRYPT_HASH);
        when(users.save(any())).thenAnswer(i -> {
            User creado = i.getArgument(0);
            creado.setId(7L);
            return creado;
        });

        AuthTokenResponse respuesta = service.register(new CitizenRegisterRequest(
                "  Ana.Perez@EXAMPLE.com  ", TestSecrets.CITIZEN_PASSWORD, " Ana ", " Pérez "));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        User guardado = captor.getValue();

        assertThat(guardado.getEmail()).isEqualTo(EMAIL);
        assertThat(guardado.getPasswordHash()).isEqualTo(TestSecrets.FAKE_BCRYPT_HASH);
        assertThat(guardado.getFirstName()).isEqualTo("Ana");
        assertThat(guardado.getLastName()).isEqualTo("Pérez");
        assertThat(guardado.getStatus()).isEqualTo(UserStatus.ACTIVE);
        // Un ciudadano no tiene identidad en el IdP: eso es lo que lo distingue de un empleado.
        assertThat(guardado.getExternalUserId()).isNull();

        assertThat(respuesta.accessToken()).isEqualTo("el-access-token");
        assertThat(respuesta.refreshToken()).isEqualTo("el-refresh-token");
        assertThat(respuesta.tokenType()).isEqualTo("Bearer");
        assertThat(respuesta.expiresIn()).isEqualTo(1800L);
        assertThat(respuesta.userId()).isEqualTo(7L);
        assertThat(respuesta.role()).isEqualTo("USER");
    }

    @Test
    void rechazaUnEmailYaRegistrado() {
        when(users.existsByEmail(EMAIL)).thenReturn(true);

        CitizenRegisterRequest request = new CitizenRegisterRequest(EMAIL, TestSecrets.CITIZEN_PASSWORD, "Ana", "Pérez");
        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        verify(users, never()).save(any());
    }

    @Test
    void fallaSiNoExisteElRolDeCiudadano() {
        when(users.existsByEmail(EMAIL)).thenReturn(false);
        when(roles.findByName("USER")).thenReturn(Optional.empty());

        CitizenRegisterRequest request = new CitizenRegisterRequest(EMAIL, TestSecrets.CITIZEN_PASSWORD, "Ana", "Pérez");
        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("USER");
    }

    // --- Login ---

    @Test
    void ingresaYLimpiaElContadorDeIntentos() {
        User user = ciudadano(UserStatus.ACTIVE, TestSecrets.FAKE_BCRYPT_HASH);
        when(users.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(encoder.matches(TestSecrets.CITIZEN_PASSWORD, TestSecrets.FAKE_BCRYPT_HASH)).thenReturn(true);
        when(attempts.isLocked(user)).thenReturn(false);
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));

        AuthTokenResponse respuesta = service.login(new CitizenLoginRequest(EMAIL, TestSecrets.CITIZEN_PASSWORD));

        assertThat(respuesta.userId()).isEqualTo(7L);
        assertThat(respuesta.email()).isEqualTo(EMAIL);
        verify(attempts).registerSuccess(user);
    }

    @Test
    void conLaContrasenaIncorrectaSumaUnFalloYDevuelve401() {
        User user = ciudadano(UserStatus.ACTIVE, TestSecrets.FAKE_BCRYPT_HASH);
        when(users.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(encoder.matches(TestSecrets.WRONG_PASSWORD, TestSecrets.FAKE_BCRYPT_HASH)).thenReturn(false);
        when(attempts.isLocked(user)).thenReturn(false);
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));

        CitizenLoginRequest request = new CitizenLoginRequest(EMAIL, TestSecrets.WRONG_PASSWORD);
        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasFieldOrPropertyWithValue("code", CitizenAuthService.CODE_INVALID_CREDENTIALS);

        verify(attempts).registerFailure(user);
        verify(users).save(user);
    }

    /**
     * Con la cuenta inexistente se compara igual contra un hash de descarte. Sin eso, el tiempo de
     * respuesta enumera cuentas igual de bien que un mensaje distinto.
     */
    @Test
    void conUnEmailInexistenteIgualEjecutaElEncoder() {
        when(users.findByEmailForUpdate(EMAIL)).thenReturn(Optional.empty());

        CitizenLoginRequest request = new CitizenLoginRequest(EMAIL, TestSecrets.CITIZEN_PASSWORD);
        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasFieldOrPropertyWithValue("code", CitizenAuthService.CODE_INVALID_CREDENTIALS);

        verify(encoder).matches(anyString(), any());
        verify(attempts, never()).registerFailure(any());
    }

    /** Un empleado del IdP no tiene contraseña local: mismo 401, sin pistas. */
    @Test
    void unaCuentaSinContrasenaLocalDevuelveElMismo401() {
        when(users.findByEmailForUpdate(EMAIL))
                .thenReturn(Optional.of(ciudadano(UserStatus.ACTIVE, null)));

        CitizenLoginRequest request = new CitizenLoginRequest(EMAIL, TestSecrets.CITIZEN_PASSWORD);
        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasFieldOrPropertyWithValue("code", CitizenAuthService.CODE_INVALID_CREDENTIALS);
    }

    @Test
    void bloqueadaConLaContrasenaCorrectaAvisaDelBloqueo() {
        User user = ciudadano(UserStatus.ACTIVE, TestSecrets.FAKE_BCRYPT_HASH);
        when(users.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(encoder.matches(TestSecrets.CITIZEN_PASSWORD, TestSecrets.FAKE_BCRYPT_HASH)).thenReturn(true);
        when(attempts.isLocked(user)).thenReturn(true);

        CitizenLoginRequest request = new CitizenLoginRequest(EMAIL, TestSecrets.CITIZEN_PASSWORD);
        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasFieldOrPropertyWithValue("code", CitizenAuthService.CODE_ACCOUNT_LOCKED);
    }

    /**
     * Bloqueada y con la contraseña incorrecta devuelve el 401 genérico: si avisara del bloqueo,
     * sería un oráculo que confirma cuál es la contraseña válida. Tampoco toca el contador, para
     * que nadie pueda mantener una cuenta bloqueada indefinidamente.
     */
    @Test
    void bloqueadaConLaContrasenaIncorrectaNoDelataNada() {
        User user = ciudadano(UserStatus.ACTIVE, TestSecrets.FAKE_BCRYPT_HASH);
        when(users.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(encoder.matches(TestSecrets.WRONG_PASSWORD, TestSecrets.FAKE_BCRYPT_HASH)).thenReturn(false);
        when(attempts.isLocked(user)).thenReturn(true);

        CitizenLoginRequest request = new CitizenLoginRequest(EMAIL, TestSecrets.WRONG_PASSWORD);
        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasFieldOrPropertyWithValue("code", CitizenAuthService.CODE_INVALID_CREDENTIALS);

        verify(attempts, never()).registerFailure(any());
    }

    @Test
    void unaCuentaBloqueadaPorAdministracionNoIngresa() {
        User user = ciudadano(UserStatus.BLOCKED, TestSecrets.FAKE_BCRYPT_HASH);
        when(users.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(user));
        when(encoder.matches(TestSecrets.CITIZEN_PASSWORD, TestSecrets.FAKE_BCRYPT_HASH)).thenReturn(true);
        when(attempts.isLocked(user)).thenReturn(false);

        CitizenLoginRequest request = new CitizenLoginRequest(EMAIL, TestSecrets.CITIZEN_PASSWORD);
        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(attempts, never()).registerSuccess(any());
    }

    // --- Refresh ---

    @Test
    void elRefreshDevuelveUnParNuevo() {
        User user = ciudadano(UserStatus.ACTIVE, TestSecrets.FAKE_BCRYPT_HASH);
        when(refreshTokens.rotate("el-viejo"))
                .thenReturn(new RefreshTokenService.Rotation(user, "el-nuevo"));

        AuthTokenResponse respuesta = service.refresh(new TokenRefreshRequest("el-viejo"));

        assertThat(respuesta.refreshToken()).isEqualTo("el-nuevo");
        assertThat(respuesta.accessToken()).isEqualTo("el-access-token");
        assertThat(respuesta.userId()).isEqualTo(7L);
        // No abre una sesión nueva: continúa la que ya existía.
        verify(refreshTokens, never()).issueForNewSession(any());
    }

    @Test
    void elRefreshDeUnaCuentaBloqueadaNoRenueva() {
        when(refreshTokens.rotate("el-viejo")).thenReturn(new RefreshTokenService.Rotation(
                ciudadano(UserStatus.BLOCKED, TestSecrets.FAKE_BCRYPT_HASH), "el-nuevo"));

        TokenRefreshRequest request = new TokenRefreshRequest("el-viejo");
        assertThatThrownBy(() -> service.refresh(request))
                .isInstanceOf(ForbiddenOperationException.class);
    }
}
