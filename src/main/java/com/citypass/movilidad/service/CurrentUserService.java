package com.citypass.movilidad.service;

import com.citypass.movilidad.client.FederatedUserInfo;
import com.citypass.movilidad.client.FederatedUserInfoClient;
import com.citypass.movilidad.config.AuthProperties;
import com.citypass.movilidad.config.CitizenAuthProperties;
import com.citypass.movilidad.config.FederatedRoleResolver;
import com.citypass.movilidad.config.FederatedJwtClaims;
import com.citypass.movilidad.dto.CurrentUserResponse;
import com.citypass.movilidad.exception.ForbiddenOperationException;
import com.citypass.movilidad.exception.UnauthorizedException;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.UserStatus;
import com.citypass.movilidad.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Resuelve la persona autenticada del Login Federado a la fila local de {@code users}.
 *
 * <p>El {@code sub} del token (el employeeNumber, p. ej. {@code U000042}) es la única clave
 * estable, y se guarda en {@code external_user_id}. Como todas las FK del esquema apuntan a
 * {@code users.id}, la fila local se crea en el primer request autenticado de cada persona.
 *
 * <p>{@code users.role} queda como snapshot denormalizado: la autorización la decide el token
 * vía las authorities, no esta columna.
 */
@Service
public class CurrentUserService {

    private final UserRepository userRepository;
    private final FederatedUserProvisioner provisioner;
    private final FederatedRoleResolver roleResolver;
    private final FederatedUserInfoClient userInfoClient;
    private final AuthProperties authProperties;
    private final CitizenAuthProperties citizenProperties;
    private final boolean localMode;

    public CurrentUserService(UserRepository userRepository,
                              FederatedUserProvisioner provisioner,
                              FederatedRoleResolver roleResolver,
                              FederatedUserInfoClient userInfoClient,
                              AuthProperties authProperties,
                              CitizenAuthProperties citizenProperties,
                              @Value("${security.local.enabled:true}") boolean localMode) {
        this.userRepository = userRepository;
        this.provisioner = provisioner;
        this.roleResolver = roleResolver;
        this.userInfoClient = userInfoClient;
        this.authProperties = authProperties;
        this.citizenProperties = citizenProperties;
        this.localMode = localMode;
    }

    /**
     * Id local de la persona autenticada. En modo local no hay token, así que cae al usuario
     * configurado en {@code movilidad.auth.local-user-id} (el demo de la V3).
     */
    @Transactional
    public Long currentUserId() {
        return resolveCurrentUser().getId();
    }

    /** La entidad de la persona autenticada, dándola de alta si es su primer request. */
    @Transactional
    public User requireCurrentUser() {
        return resolveCurrentUser();
    }

    /**
     * La persona autenticada con su tipo y su rol efectivo, para {@code GET /api/v1/users/me}.
     *
     * <p>El rol sale del token y no de {@code users.role}: esa columna es un snapshot, y la
     * autorización la decide el token en todo el resto del diseño. Para el personal municipal es
     * el mismo cálculo que alimenta las authorities ({@link FederatedRoleResolver}), así que lo
     * que ve el front coincide con lo que después aplica {@code @PreAuthorize}.
     *
     * <p>Sin token solo se llega acá en modo local, con el usuario demo de la V3, que es una
     * cuenta sin identidad en el IdP: se describe como ciudadano. En modo seguro
     * {@link #resolveCurrentUser()} ya cortó con 401.
     */
    @Transactional
    public CurrentUserResponse describeCurrentUser() {
        User user = resolveCurrentUser();
        Optional<Jwt> jwt = currentJwt();
        if (jwt.isEmpty() || isCitizenToken(jwt.get())) {
            return describe(user, CurrentUserResponse.TYPE_CITIZEN, CurrentUserResponse.ROLE_CITIZEN,
                    List.of());
        }
        Jwt token = jwt.get();
        return describe(user, CurrentUserResponse.TYPE_STAFF, roleResolver.resolveRole(token),
                roleResolver.groupsOf(token));
    }

    private static CurrentUserResponse describe(User user, String type, String role,
                                                List<String> groups) {
        return new CurrentUserResponse(user.getId(), type, role, user.getFirstName(),
                user.getLastName(), user.getEmail(), groups);
    }

    /**
     * Sin {@code @Transactional} a propósito: lo llaman los métodos públicos, que ya abren la
     * transacción. Llamar a uno público desde el otro por {@code this} saltearía el proxy.
     */
    private User resolveCurrentUser() {
        Optional<Jwt> jwt = currentJwt();
        if (jwt.isEmpty()) {
            return localFallbackUser();
        }
        Jwt token = jwt.get();
        return isCitizenToken(token) ? resolveCitizen(token) : resolveFederatedUser(token);
    }

    /**
     * Se discrimina por el claim iss, que es lo único que distingue con certeza y que el decoder
     * ya validó antes de llegar acá. Ramificar por "el sub parsea como número" sería adivinar: un
     * employeeNumber numérico del IdP apuntaría a la fila de otra persona.
     */
    private boolean isCitizenToken(Jwt token) {
        return citizenProperties.issuer().equals(token.getClaimAsString(JwtClaimNames.ISS));
    }

    /**
     * Sin token solo se opera en modo local, con el usuario demo configurado.
     *
     * <p>En modo seguro llegar acá sin identidad sería imputarle a ese usuario las acciones de
     * cualquiera, en silencio. Por eso es un 401 y no un fallback.
     */
    private User localFallbackUser() {
        if (!localMode) {
            throw new UnauthorizedException("El request no tiene una identidad asociada");
        }
        Long localUserId = authProperties.localUserId();
        return userRepository.findById(localUserId)
                .orElseThrow(() -> new IllegalStateException(
                        "El usuario local configurado no existe: " + localUserId));
    }

    /**
     * Ciudadano con cuenta local: el {@code sub} es su id.
     *
     * <p>No da de alta a nadie ni reconcilia rol, al contrario de la rama federada: la cuenta se
     * creó en el registro, y el rol de un ciudadano no se deriva de grupos LDAP. Si la fila no
     * existe, el token sobrevivió a la baja de la cuenta.
     */
    private User resolveCitizen(Jwt jwt) {
        User user = userRepository.findById(citizenId(jwt))
                .orElseThrow(() -> new UnauthorizedException("La identidad del token ya no existe"));
        requireActive(user);
        return user;
    }

    private static Long citizenId(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new UnauthorizedException("El token no trae el claim sub");
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException ex) {
            throw new UnauthorizedException("El sub del token no es una identidad local válida");
        }
    }

    private static Optional<Jwt> currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        return Optional.of(jwt);
    }

    private User resolveFederatedUser(Jwt jwt) {
        String externalUserId = jwt.getSubject();
        if (externalUserId == null || externalUserId.isBlank()) {
            throw new IllegalStateException("El token no trae el claim sub, que es la clave de la persona");
        }
        User user = userRepository.findByExternalUserId(externalUserId)
                .map(existing -> reconcile(existing, jwt))
                .orElseGet(() -> provisioner.provision(
                        externalUserId, profileOf(jwt), roleResolver.resolveRole(jwt)));
        requireActive(user);
        return user;
    }

    /**
     * Perfil con el que se da de alta a la persona. Si el IdP no contesta, el nombre cae a
     * {@code preferred_username} y el resto queda nulo: la persona opera igual y la fila se
     * completa en un request posterior.
     */
    private FederatedUserInfo profileOf(Jwt jwt) {
        return userInfoClient.fetch(jwt.getTokenValue(), jwt.getSubject())
                .orElseGet(() -> new FederatedUserInfo(
                        null, jwt.getClaimAsString(FederatedJwtClaims.PREFERRED_USERNAME), null));
    }

    /**
     * Baja al snapshot local lo que haya cambiado: el rol según los grupos del token, y el perfil
     * si quedó incompleto en un alta previa. Un solo {@code save} para los dos.
     */
    private User reconcile(User user, Jwt jwt) {
        boolean changed = applyRole(user, jwt);
        changed = applyMissingProfile(user, jwt) || changed;
        return changed ? userRepository.save(user) : user;
    }

    /**
     * Un cambio de grupos en el panel del delegado se refleja al próximo login o refresh (máximo
     * 15 min, la vida del access token); acá se baja ese cambio a la fila local.
     */
    private boolean applyRole(User user, Jwt jwt) {
        String expectedRole = roleResolver.resolveRole(jwt);
        if (user.getRole() != null && expectedRole.equals(user.getRole().getName())) {
            return false;
        }
        user.setRole(provisioner.roleNamed(expectedRole));
        return true;
    }

    /**
     * Completa las filas que se crearon sin email o sin apellido porque el IdP no estaba
     * disponible. No es cosmético: el contrato de eventos del bus exige esos campos, así que una
     * fila incompleta hace que los eventos de esa persona nunca se publiquen.
     */
    private boolean applyMissingProfile(User user, Jwt jwt) {
        if (user.getEmail() != null && user.getLastName() != null) {
            return false;
        }
        Optional<FederatedUserInfo> consultado =
                userInfoClient.fetch(jwt.getTokenValue(), jwt.getSubject());
        if (consultado.isEmpty()) {
            return false;
        }
        FederatedUserInfo profile = consultado.get();
        boolean changed = false;
        // Solo se completa lo que falta: lo que el IdP no informe no borra lo que ya había.
        if (user.getEmail() == null && profile.email() != null) {
            user.setEmail(profile.email());
            changed = true;
        }
        if (user.getLastName() == null && profile.lastName() != null) {
            user.setLastName(profile.lastName());
            changed = true;
        }
        // El nombre real del directorio reemplaza al preferred_username del alta degradada.
        if (profile.firstName() != null && !profile.firstName().equals(user.getFirstName())) {
            user.setFirstName(profile.firstName());
            changed = true;
        }
        return changed;
    }

    /** Kill-switch local: una fila bloqueada se rechaza aunque el token sea válido. */
    private static void requireActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenOperationException(
                    "El usuario no está habilitado: " + user.getId());
        }
    }
}
