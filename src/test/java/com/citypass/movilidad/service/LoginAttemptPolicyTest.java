package com.citypass.movilidad.service;

import com.citypass.movilidad.config.CitizenAuthProperties;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La lógica temporal del bloqueo, que es la parte fácil de equivocar: 5 intentos dentro de una
 * ventana de 15 minutos bloquean 10 minutos.
 */
class LoginAttemptPolicyTest {

    private final MutableClock clock = MutableClock.fija();
    private final LoginAttemptPolicy policy = new LoginAttemptPolicy(
            new CitizenAuthProperties("iss", "aud", null, Duration.ofMinutes(30),
                    Duration.ofDays(30), 5, Duration.ofMinutes(15), Duration.ofMinutes(10)),
            clock);

    private static User cuenta() {
        return User.builder().id(1L).build();
    }

    @Test
    void elQuintoFalloSeguidoBloquea() {
        User user = cuenta();

        for (int i = 0; i < 4; i++) {
            policy.registerFailure(user);
        }
        assertThat(user.getFailedLoginAttempts()).isEqualTo(4);
        assertThat(policy.isLocked(user)).isFalse();

        policy.registerFailure(user);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(policy.isLocked(user)).isTrue();
        assertThat(user.getLockedUntil()).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
    }

    @Test
    void elBloqueoVenceALosDiezMinutos() {
        User user = cuenta();
        for (int i = 0; i < 5; i++) {
            policy.registerFailure(user);
        }

        clock.advance(Duration.ofMinutes(9));
        assertThat(policy.isLocked(user)).isTrue();

        clock.advance(Duration.ofMinutes(2));
        assertThat(policy.isLocked(user)).isFalse();
    }

    /** Sin la ventana el contador sería acumulativo de por vida. */
    @Test
    void unFalloPasadaLaVentanaReiniciaElContador() {
        User user = cuenta();
        policy.registerFailure(user);
        policy.registerFailure(user);
        assertThat(user.getFailedLoginAttempts()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(16));
        policy.registerFailure(user);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(policy.isLocked(user)).isFalse();
    }

    @Test
    void dentroDeLaVentanaElContadorSigue() {
        User user = cuenta();
        policy.registerFailure(user);

        clock.advance(Duration.ofMinutes(14));
        policy.registerFailure(user);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(2);
    }

    /**
     * Cumplido el bloqueo la persona arranca de cero. Si el contador siguiera, como la ventana
     * (15 min) es más larga que el bloqueo (10 min), un solo error después de cumplirlo volvería
     * a bloquear al instante y quien olvidó su contraseña quedaría afuera casi para siempre.
     */
    @Test
    void cumplidoElBloqueoElContadorArrancaDeCero() {
        User user = cuenta();
        for (int i = 0; i < 5; i++) {
            policy.registerFailure(user);
        }
        clock.advance(Duration.ofMinutes(11));
        assertThat(policy.isLocked(user)).isFalse();

        policy.registerFailure(user);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(policy.isLocked(user)).isFalse();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void elExitoLimpiaElContadorYElBloqueo() {
        User user = cuenta();
        for (int i = 0; i < 5; i++) {
            policy.registerFailure(user);
        }

        policy.registerSuccess(user);

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLastFailedLoginAt()).isNull();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(policy.isLocked(user)).isFalse();
    }

    @Test
    void unaCuentaSinFallosNoEstaBloqueada() {
        assertThat(policy.isLocked(cuenta())).isFalse();
    }

}
