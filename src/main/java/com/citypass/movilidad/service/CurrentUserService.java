package com.citypass.movilidad.service;

import com.citypass.movilidad.config.AuthProperties;
import com.citypass.movilidad.config.FederatedRoleResolver;
import com.citypass.movilidad.config.FederatedJwtClaims;
import com.citypass.movilidad.exception.ForbiddenOperationException;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.UserStatus;
import com.citypass.movilidad.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final AuthProperties authProperties;

    public CurrentUserService(UserRepository userRepository,
                              FederatedUserProvisioner provisioner,
                              FederatedRoleResolver roleResolver,
                              AuthProperties authProperties) {
        this.userRepository = userRepository;
        this.provisioner = provisioner;
        this.roleResolver = roleResolver;
        this.authProperties = authProperties;
    }

    /**
     * Id local de la persona autenticada. En modo local no hay token, así que cae al usuario
     * configurado en {@code movilidad.auth.local-user-id} (el demo de la V3).
     */
    @Transactional
    public Long currentUserId() {
        return requireCurrentUser().getId();
    }

    /** La entidad de la persona autenticada, dándola de alta si es su primer request. */
    @Transactional
    public User requireCurrentUser() {
        Optional<Jwt> jwt = currentJwt();
        if (jwt.isPresent()) {
            return resolveUser(jwt.get());
        }
        Long localUserId = authProperties.localUserId();
        return userRepository.findById(localUserId)
                .orElseThrow(() -> new IllegalStateException(
                        "El usuario local configurado no existe: " + localUserId));
    }

    private static Optional<Jwt> currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        return Optional.of(jwt);
    }

    private User resolveUser(Jwt jwt) {
        String externalUserId = jwt.getSubject();
        if (externalUserId == null || externalUserId.isBlank()) {
            throw new IllegalStateException("El token no trae el claim sub, que es la clave de la persona");
        }
        User user = userRepository.findByExternalUserId(externalUserId)
                .map(existing -> reconcileRole(existing, jwt))
                .orElseGet(() -> provisioner.provision(
                        externalUserId,
                        jwt.getClaimAsString(FederatedJwtClaims.PREFERRED_USERNAME),
                        roleResolver.resolveRole(jwt)));
        requireActive(user);
        return user;
    }

    /**
     * Un cambio de grupos en el panel del delegado se refleja al próximo login o refresh (máximo
     * 15 min, la vida del access token); acá se baja ese cambio a la fila local.
     */
    private User reconcileRole(User user, Jwt jwt) {
        String expectedRole = roleResolver.resolveRole(jwt);
        if (user.getRole() != null && expectedRole.equals(user.getRole().getName())) {
            return user;
        }
        user.setRole(provisioner.roleNamed(expectedRole));
        return userRepository.save(user);
    }

    /** Kill-switch local: una fila bloqueada se rechaza aunque el token sea válido. */
    private static void requireActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenOperationException(
                    "El usuario no está habilitado: " + user.getExternalUserId());
        }
    }
}
