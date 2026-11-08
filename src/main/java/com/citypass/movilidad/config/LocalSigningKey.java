package com.citypass.movilidad.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Resuelve la clave con la que se firman los tokens de ciudadano.
 *
 * <p>HS256 y no RS256 a propósito: el emisor y el único verificador son el mismo proceso. No hay
 * JWKS que publicar y ningún otro módulo consume estos tokens, así que lo que agrega una firma
 * asimétrica —verificar sin poder emitir— no tiene consumidor acá. Lo que sí agregaría es
 * serializar un par de claves, parsearlo y rotar dos piezas acopladas. El día que otro módulo
 * quiera validarlos, esta clase es el único lugar que conoce el algoritmo.
 */
public final class LocalSigningKey {

    /** HS256 lo exige: con menos, Nimbus falla con KeyLengthException al firmar. */
    private static final int MIN_LENGTH_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";

    private static final Logger LOG = LoggerFactory.getLogger(LocalSigningKey.class);

    /** Una sola instancia: SecureRandom es thread-safe y crearla en cada llamada es lo que marca S2119. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private LocalSigningKey() {
    }

    /**
     * Con el secreto configurado lo usa. Sin él, en modo local genera uno aleatorio y en modo
     * seguro falla al arrancar.
     *
     * <p>Generar uno aleatorio invalida los tokens en cada reinicio, lo cual en desarrollo es
     * irrelevante y evita tener una clave de firma commiteada en el repo. En modo seguro sería
     * inaceptable —Render reinicia solo cuando despierta el servicio, y sería un logout masivo—
     * así que ahí el arranque muere con un mensaje explícito en lugar de firmar con una clave que
     * nadie eligió.
     */
    public static SecretKey resolve(String configured, boolean localMode) {
        if (configured != null && !configured.isBlank()) {
            return fromConfigured(configured);
        }
        if (!localMode) {
            throw new IllegalStateException(
                    "Falta movilidad.citizen-auth.secret (CITIZEN_AUTH_SECRET) y "
                            + "security.local.enabled es false: no se puede emitir tokens de "
                            + "ciudadano sin una clave de firma elegida");
        }
        LOG.warn("Sin CITIZEN_AUTH_SECRET: se genera una clave de firma aleatoria. Los tokens de "
                + "ciudadano dejan de valer en cada reinicio. Solo sirve para desarrollo.");
        byte[] aleatoria = new byte[MIN_LENGTH_BYTES];
        RANDOM.nextBytes(aleatoria);
        return new SecretKeySpec(aleatoria, ALGORITHM);
    }

    private static SecretKey fromConfigured(String configured) {
        byte[] secret;
        try {
            secret = Base64.getUrlDecoder().decode(configured.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "movilidad.citizen-auth.secret no es base64url válido. Para generar uno: "
                            + "openssl rand -base64 32 | tr '+/' '-_' | tr -d '='", ex);
        }
        if (secret.length < MIN_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "movilidad.citizen-auth.secret decodifica a " + secret.length
                            + " bytes y HS256 exige al menos " + MIN_LENGTH_BYTES);
        }
        return new SecretKeySpec(secret, ALGORITHM);
    }
}
