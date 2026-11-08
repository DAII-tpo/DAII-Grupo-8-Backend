package com.citypass.movilidad.service;

import com.citypass.movilidad.config.CitizenAuthProperties;
import com.citypass.movilidad.model.User;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Política de intentos fallidos de login.
 *
 * <p>Sin estado ni repositorios: opera sobre la entidad que le pasan y un reloj inyectado, así que
 * la lógica temporal —que es la parte fácil de equivocar— se prueba sin base de datos.
 *
 * <p>Lo que <strong>no</strong> cubre, y conviene no confundir: el límite es por cuenta, no por IP.
 * No frena a quien prueba una contraseña contra mil cuentas distintas, ni un flood de registros.
 * Eso necesita infraestructura que el proyecto no tiene.
 */
@Component
public class LoginAttemptPolicy {

    private final CitizenAuthProperties properties;
    private final Clock clock;

    public LoginAttemptPolicy(CitizenAuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isLocked(User user) {
        Instant until = user.getLockedUntil();
        return until != null && clock.instant().isBefore(until);
    }

    /** Suma un fallo y bloquea al alcanzar el máximo. */
    public void registerFailure(User user) {
        Instant now = clock.instant();
        int attempts = continuesStreak(user, now) ? user.getFailedLoginAttempts() + 1 : 1;
        user.setFailedLoginAttempts(attempts);
        user.setLastFailedLoginAt(now);
        user.setLockedUntil(attempts >= properties.maxFailedAttempts()
                ? now.plus(properties.lockDuration())
                : null);
    }

    public void registerSuccess(User user) {
        user.setFailedLoginAttempts(0);
        user.setLastFailedLoginAt(null);
        user.setLockedUntil(null);
    }

    /**
     * La racha continúa solo si el último fallo entra en la ventana y no quedó un bloqueo cumplido.
     *
     * <p>Lo segundo importa: a este método se llega únicamente cuando la cuenta no está bloqueada,
     * así que un {@code lockedUntil} con valor significa que el castigo ya venció y la persona
     * arranca de cero. Sin esa regla, como la ventana es más larga que el bloqueo, un solo error
     * después de cumplirlo volvería a bloquear al instante, y quien olvidó su contraseña quedaría
     * afuera casi para siempre.
     */
    private boolean continuesStreak(User user, Instant now) {
        if (user.getLockedUntil() != null) {
            return false;
        }
        Instant last = user.getLastFailedLoginAt();
        return last != null && last.isAfter(now.minus(properties.failedAttemptWindow()));
    }
}
