package com.citypass.movilidad.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Reloj movible, para recorrer vencimientos y ventanas de tiempo sin esperarlos.
 *
 * <p>Compartido a propósito: cada test que lo definía por su cuenta era la misma clase copiada,
 * y la duplicación en tests también la cuenta el análisis estático.
 */
public final class MutableClock extends Clock {

    private Instant now;

    private MutableClock(Instant inicio) {
        this.now = inicio;
    }

    /** Instante fijo y reproducible. Sirve cuando el tiempo absoluto no importa. */
    public static MutableClock fija() {
        return new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
    }

    /**
     * Arranca en el instante real, truncado a segundos.
     *
     * <p>Hace falta cuando lo que se prueba pasa por un validador que usa el reloj del sistema y
     * no el inyectado — {@code JwtTimestampValidator}, por ejemplo: con un reloj fijo en el pasado,
     * los tokens saldrían ya vencidos. El truncado es porque {@code exp} e {@code iat} se
     * serializan en segundos, y si no las comparaciones fallan por los nanos.
     */
    public static MutableClock real() {
        return new MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    public static MutableClock desde(Instant inicio) {
        return new MutableClock(inicio);
    }

    /** Mueve el reloj. Admite duraciones negativas, para emitir algo con fecha pasada. */
    public void advance(Duration cuanto) {
        now = now.plus(cuanto);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
