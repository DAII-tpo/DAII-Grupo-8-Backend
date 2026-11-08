package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.AuthTokenResponse;
import com.citypass.movilidad.dto.CitizenLoginRequest;
import com.citypass.movilidad.dto.CitizenRegisterRequest;
import com.citypass.movilidad.dto.TokenRefreshRequest;
import com.citypass.movilidad.exception.EmailAlreadyRegisteredException;
import com.citypass.movilidad.exception.ForbiddenOperationException;
import com.citypass.movilidad.exception.UnauthorizedException;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.UserStatus;
import com.citypass.movilidad.repository.RoleRepository;
import com.citypass.movilidad.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Registro e ingreso de ciudadanos con cuenta local.
 *
 * <p>Convive con el Login Federado sin mezclarse: un ciudadano es una fila con email y
 * {@code password_hash}, y {@code external_user_id} en NULL; un empleado del IdP es lo inverso.
 */
@Service
public class CitizenAuthService {

    /**
     * Los ciudadanos siempre reciben este rol. La autorización real la decide la authority
     * {@code ROLE_CITIZEN} que otorga el converter según el emisor del token, así que esta columna
     * es un snapshot y no la fuente de verdad.
     */
    private static final String CITIZEN_ROLE = "USER";

    public static final String CODE_INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String CODE_ACCOUNT_LOCKED = "ACCOUNT_LOCKED";

    private static final String BEARER = "Bearer";

    /** Un solo mensaje para todos los fallos de credenciales, para no distinguir casos. */
    private static final String MENSAJE_CREDENCIALES = "Email o contraseña incorrectos";

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final CitizenTokenService tokens;
    private final RefreshTokenService refreshTokens;
    private final LoginAttemptPolicy attempts;

    /**
     * Hash de descarte contra el que se compara cuando la cuenta no existe.
     *
     * <p>Sin esto, un email inexistente responde en un milisegundo y uno existente en ochenta, con
     * lo que el tiempo de respuesta enumera cuentas igual de bien que un mensaje distinto.
     */
    private final String hashDeDescarte;

    public CitizenAuthService(UserRepository users, RoleRepository roles, PasswordEncoder encoder,
                              CitizenTokenService tokens, RefreshTokenService refreshTokens,
                              LoginAttemptPolicy attempts) {
        this.users = users;
        this.roles = roles;
        this.encoder = encoder;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.attempts = attempts;
        this.hashDeDescarte = encoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthTokenResponse register(CitizenRegisterRequest request) {
        String email = normalize(request.email());
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }
        User creado = users.save(User.builder()
                .email(email)
                .passwordHash(encoder.encode(request.password()))
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .role(roles.findByName(CITIZEN_ROLE).orElseThrow(() -> new IllegalStateException(
                        "No existe el rol '" + CITIZEN_ROLE + "' en la tabla roles")))
                .status(UserStatus.ACTIVE)
                .build());
        return issue(creado);
    }

    /**
     * Ingreso con email y contraseña.
     *
     * <p>{@code noRollbackFor} es obligatorio y no un detalle: el contador de intentos se guarda
     * justo antes de lanzar el 401, y con el rollback por defecto ese UPDATE se perdería, con lo
     * que el bloqueo por intentos fallidos nunca llegaría a dispararse.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthTokenResponse login(CitizenLoginRequest request) {
        String email = normalize(request.email());
        // Con lock pesimista: serializa los intentos concurrentes del mismo email, que es lo que
        // hace que dos fallos simultáneos cuenten dos y no uno.
        User user = users.findByEmailForUpdate(email).orElse(null);

        if (user == null || user.getPasswordHash() == null) {
            // Se compara igual, contra el hash de descarte, para no delatar por tiempo si la
            // cuenta existe. El password_hash nulo cubre a los empleados del IdP, que no tienen
            // contraseña local y no pueden entrar por acá.
            encoder.matches(request.password(), hashDeDescarte);
            throw invalidCredentials();
        }

        boolean correcta = encoder.matches(request.password(), user.getPasswordHash());

        if (attempts.isLocked(user)) {
            // El código de bloqueo solo si la contraseña era correcta: si no, el bloqueo sería un
            // oráculo que confirma cuál es la contraseña válida. No se toca el contador, para que
            // alguien no pueda mantener una cuenta bloqueada indefinidamente.
            throw correcta
                    ? new UnauthorizedException(CODE_ACCOUNT_LOCKED,
                            "La cuenta está bloqueada temporalmente por intentos fallidos")
                    : invalidCredentials();
        }

        if (!correcta) {
            attempts.registerFailure(user);
            users.save(user);
            throw invalidCredentials();
        }

        // Después de verificar la contraseña: antes, revelaría que la cuenta existe.
        requireActive(user);
        attempts.registerSuccess(user);
        return issue(users.save(user));
    }

    /** Rota el refresh token y devuelve un par nuevo. */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthTokenResponse refresh(TokenRefreshRequest request) {
        RefreshTokenService.Rotation rotacion = refreshTokens.rotate(request.refreshToken());
        requireActive(rotacion.user());
        return response(rotacion.user(), rotacion.refreshToken());
    }

    private AuthTokenResponse issue(User user) {
        return response(user, refreshTokens.issueForNewSession(user));
    }

    private AuthTokenResponse response(User user, String refreshToken) {
        return new AuthTokenResponse(
                tokens.issueAccessToken(user), BEARER, tokens.accessTokenTtl().toSeconds(),
                refreshToken, user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                user.getRole() == null ? null : user.getRole().getName());
    }

    /** Kill-switch local: una cuenta bloqueada o inactiva no entra aunque la contraseña sea válida. */
    private static void requireActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenOperationException("La cuenta no está habilitada: " + user.getId());
        }
    }

    private UnauthorizedException invalidCredentials() {
        return new UnauthorizedException(CODE_INVALID_CREDENTIALS, MENSAJE_CREDENCIALES);
    }

    /** El unique de email es case-insensitive en MySQL, pero se normaliza igual en la app. */
    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
