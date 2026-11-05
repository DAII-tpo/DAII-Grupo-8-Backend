package com.citypass.movilidad.service;

import com.citypass.movilidad.config.AuthProperties;
import com.citypass.movilidad.model.Role;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.UserStatus;
import com.citypass.movilidad.repository.RoleRepository;
import com.citypass.movilidad.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alta just-in-time de la identidad federada.
 *
 * <p>Vive en su propio bean y no como método privado de {@link CurrentUserService} porque
 * {@code REQUIRES_NEW} necesita pasar por el proxy de Spring: una llamada dentro de la misma
 * clase lo saltea y la transacción nueva nunca se abre.
 */
@Service
public class FederatedUserProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger(FederatedUserProvisioner.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AuthProperties authProperties;

    public FederatedUserProvisioner(UserRepository userRepository,
                                    RoleRepository roleRepository,
                                    AuthProperties authProperties) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.authProperties = authProperties;
    }

    /**
     * Crea la fila local de una persona del IdP. El access token no trae email, nombre ni
     * apellido (no existen esos claims), así que esas columnas quedan nulas en lugar de
     * rellenarse con valores inventados; {@code preferred_username} va a {@code first_name},
     * que es para mostrar en pantalla.
     *
     * <p>En una carrera entre dos requests del mismo sub, el índice único
     * {@code uk_users_external_user_id} hace fallar al segundo insert: se relee la fila ganadora
     * en lugar de dejar dos identidades para la misma persona. La transacción propia es lo que
     * permite que ese fallo no arrastre al request entero.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User provision(String externalUserId, String preferredUsername, String roleName) {
        User user = User.builder()
                .externalUserId(externalUserId)
                .firstName(preferredUsername)
                .role(roleNamed(roleName))
                .status(UserStatus.ACTIVE)
                .build();
        try {
            User created = userRepository.save(user);
            LOG.info("Alta de usuario federado: external_user_id={}", externalUserId);
            return created;
        } catch (DataIntegrityViolationException ex) {
            LOG.debug("Alta simultánea del mismo usuario federado, se relee: {}", externalUserId);
            return userRepository.findByExternalUserId(externalUserId).orElseThrow(() -> ex);
        }
    }

    /**
     * El mapeo de grupos apunta a filas de {@code roles}. Si el rol mapeado no existe se cae al
     * rol por defecto, y si tampoco existe es un error de configuración real.
     */
    public Role roleNamed(String roleName) {
        return roleRepository.findByName(roleName)
                .or(() -> roleRepository.findByName(authProperties.defaultRole()))
                .orElseThrow(() -> new IllegalStateException(
                        "No existe el rol '" + roleName + "' ni el rol por defecto '"
                                + authProperties.defaultRole() + "' en la tabla roles"));
    }

    /** Avisa al arrancar si el mapeo de grupos nombra roles que no están en la base. */
    @EventListener(ApplicationReadyEvent.class)
    public void warnAboutMissingRoles() {
        authProperties.groupRoles().values().stream().distinct()
                .filter(role -> roleRepository.findByName(role).isEmpty())
                .forEach(role -> LOG.warn(
                        "El rol '{}' está en movilidad.auth.group-roles pero no existe en la tabla roles",
                        role));
    }
}
