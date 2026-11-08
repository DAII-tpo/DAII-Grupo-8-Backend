package com.citypass.movilidad.repository;

import com.citypass.movilidad.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** El hash es único, así que identifica la sesión sin ambigüedad. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revoca de un golpe la cadena entera de rotaciones. Se usa ante el reuso de un token ya
     * revocado: en ese punto no se sabe si fue un replay o un cliente desincronizado, y lo seguro
     * es cortar toda la sesión.
     */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now "
            + "where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") String familyId, @Param("now") Instant now);

    /** Limpieza de los vencidos; sin esto la tabla crece sin techo. */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
