package com.citypass.movilidad.service;

import com.citypass.movilidad.config.CitizenAuthProperties;
import com.citypass.movilidad.exception.UnauthorizedException;
import com.citypass.movilidad.model.RefreshToken;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Emisión y rotación de los refresh tokens de los ciudadanos.
 *
 * <p>El token es opaco —256 bits de {@code SecureRandom}— y no un JWT: no hay nada que el cliente
 * deba leer, y de todos modos habría que ir a la base a chequear la revocación, así que firmarlo
 * no compraría nada.
 *
 * <p>Se guarda solo su SHA-256. No hace falta BCrypt: el token es aleatorio de 256 bits, no hay
 * diccionario que atacar, y BCrypt en cada refresh sería costo puro.
 */
@Service
public class RefreshTokenService {

    /** 256 bits: suficiente para que adivinarlo no sea una estrategia. */
    private static final int TOKEN_BYTES = 32;

    public static final String CODE_INVALID = "INVALID_REFRESH_TOKEN";

    private static final Logger LOG = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository repository;
    private final CitizenAuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository,
                               CitizenAuthProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** Lo que devuelve una rotación: de quién es la sesión y el token que la continúa. */
    public record Rotation(User user, String refreshToken) {
    }

    /** Primer token de una sesión nueva, con su propia familia. */
    @Transactional
    public String issueForNewSession(User user) {
        return issue(user, UUID.randomUUID().toString());
    }

    /**
     * Revoca el token presentado y emite el siguiente de la misma familia.
     *
     * <p>Si llega uno ya revocado se revoca la familia completa: en ese punto no se sabe si fue un
     * replay de un atacante o un cliente que se desincronizó, y lo seguro en los dos casos es
     * cortar la sesión y forzar un login.
     *
     * <p>{@code noRollbackFor} no es opcional: esa revocación se escribe <em>y después</em> se
     * lanza el 401. Con el rollback por defecto, el UPDATE se perdería y la detección de reuso no
     * haría absolutamente nada.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public Rotation rotate(String presented) {
        if (presented == null || presented.isBlank()) {
            throw invalid();
        }
        RefreshToken stored = repository.findByTokenHash(hash(presented)).orElseThrow(this::invalid);
        Instant now = clock.instant();

        if (stored.isRevoked()) {
            int revocados = repository.revokeFamily(stored.getFamilyId(), now);
            LOG.warn("Reuso de un refresh token ya revocado: se corta la familia {} ({} vivos)",
                    stored.getFamilyId(), revocados);
            throw invalid();
        }
        if (stored.isExpired(now)) {
            throw invalid();
        }

        stored.setRevokedAt(now);
        repository.save(stored);
        return new Rotation(stored.getUser(), issue(stored.getUser(), stored.getFamilyId()));
    }

    /** Corta todas las sesiones vivas de una familia. Para un logout o una baja. */
    @Transactional
    public void revokeFamilyOf(String presented) {
        repository.findByTokenHash(hash(presented))
                .ifPresent(stored -> repository.revokeFamily(stored.getFamilyId(), clock.instant()));
    }

    /**
     * Borra los vencidos. Con 30 días de vida y rotación en cada refresh, sin esto la tabla crece
     * sin techo. Corre de madrugada porque no compite con nada.
     */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void deleteExpired() {
        int borradas = repository.deleteExpiredBefore(clock.instant());
        if (borradas > 0) {
            LOG.info("Limpieza de refresh tokens vencidos: {} filas", borradas);
        }
    }

    private String issue(User user, String familyId) {
        byte[] crudo = new byte[TOKEN_BYTES];
        random.nextBytes(crudo);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(crudo);

        repository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(hash(token))
                .familyId(familyId)
                .expiresAt(clock.instant().plus(properties.refreshTokenTtl()))
                .build());
        return token;
    }

    private UnauthorizedException invalid() {
        // Un solo mensaje para desconocido, vencido, revocado y reusado: distinguirlos solo le
        // daría información a quien prueba tokens.
        return new UnauthorizedException(CODE_INVALID, "El refresh token no es válido");
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 tiene que estar disponible en la JVM", ex);
        }
    }
}
