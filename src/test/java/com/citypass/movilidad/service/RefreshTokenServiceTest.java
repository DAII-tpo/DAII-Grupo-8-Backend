package com.citypass.movilidad.service;

import com.citypass.movilidad.config.CitizenAuthProperties;
import com.citypass.movilidad.exception.UnauthorizedException;
import com.citypass.movilidad.model.RefreshToken;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rotación y detección de reuso. La propiedad que importa es que el token en claro no se guarde
 * nunca y que un token ya usado corte la sesión entera.
 */
class RefreshTokenServiceTest {

    private static final Instant AHORA = Instant.parse("2026-01-01T10:00:00Z");
    private static final String FAMILIA = "f1e2d3c4-0000-0000-0000-000000000001";

    private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    private final RefreshTokenService service = new RefreshTokenService(repository,
            new CitizenAuthProperties("iss", "aud", null, Duration.ofMinutes(30),
                    Duration.ofDays(30), 5, Duration.ofMinutes(15), Duration.ofMinutes(10)),
            Clock.fixed(AHORA, ZoneOffset.UTC));

    private static User ciudadano() {
        return User.builder().id(7L).build();
    }

    private RefreshToken guardado(String token, Instant expiresAt, Instant revokedAt) {
        return RefreshToken.builder()
                .id(1L)
                .user(ciudadano())
                .tokenHash(RefreshTokenService.hash(token))
                .familyId(FAMILIA)
                .expiresAt(expiresAt)
                .revokedAt(revokedAt)
                .build();
    }

    @Test
    void guardaElHashYNuncaElTokenEnClaro() {
        String token = service.issueForNewSession(ciudadano());

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        RefreshToken fila = captor.getValue();

        assertThat(fila.getTokenHash()).isEqualTo(RefreshTokenService.hash(token));
        assertThat(fila.getTokenHash()).isNotEqualTo(token).hasSize(64);
        assertThat(fila.getExpiresAt()).isEqualTo(AHORA.plus(Duration.ofDays(30)));
        assertThat(fila.getFamilyId()).isNotBlank();
        assertThat(fila.getRevokedAt()).isNull();
    }

    @Test
    void cadaSesionNuevaAbreSuPropiaFamilia() {
        service.issueForNewSession(ciudadano());
        service.issueForNewSession(ciudadano());

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());

        assertThat(captor.getAllValues().get(0).getFamilyId())
                .isNotEqualTo(captor.getAllValues().get(1).getFamilyId());
    }

    @Test
    void rotaRevocandoElAnteriorYConservandoLaFamilia() {
        RefreshToken vigente = guardado("el-token", AHORA.plus(Duration.ofDays(1)), null);
        when(repository.findByTokenHash(RefreshTokenService.hash("el-token")))
                .thenReturn(Optional.of(vigente));

        RefreshTokenService.Rotation rotacion = service.rotate("el-token");

        assertThat(rotacion.user().getId()).isEqualTo(7L);
        assertThat(rotacion.refreshToken()).isNotBlank().isNotEqualTo("el-token");
        assertThat(vigente.getRevokedAt()).isEqualTo(AHORA);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anySatisfy(fila -> assertThat(fila.getFamilyId()).isEqualTo(FAMILIA));
    }

    /** Reusar uno ya revocado corta la familia entera: o es un replay o el cliente se desincronizó. */
    @Test
    void elReusoDeUnTokenRevocadoCortaLaFamilia() {
        when(repository.findByTokenHash(RefreshTokenService.hash("reusado")))
                .thenReturn(Optional.of(guardado("reusado", AHORA.plus(Duration.ofDays(1)), AHORA)));

        assertThatThrownBy(() -> service.rotate("reusado"))
                .isInstanceOf(UnauthorizedException.class)
                .hasFieldOrPropertyWithValue("code", RefreshTokenService.CODE_INVALID);

        verify(repository).revokeFamily(FAMILIA, AHORA);
    }

    @Test
    void rechazaUnTokenVencidoSinCortarLaFamilia() {
        when(repository.findByTokenHash(RefreshTokenService.hash("viejo")))
                .thenReturn(Optional.of(guardado("viejo", AHORA.minus(Duration.ofMinutes(1)), null)));

        assertThatThrownBy(() -> service.rotate("viejo"))
                .isInstanceOf(UnauthorizedException.class);

        verify(repository, never()).revokeFamily(any(), any());
    }

    @Test
    void rechazaUnTokenDesconocido() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("inventado"))
                .isInstanceOf(UnauthorizedException.class)
                .hasFieldOrPropertyWithValue("code", RefreshTokenService.CODE_INVALID);
    }

    @Test
    void rechazaUnTokenAusenteSinConsultarLaBase() {
        assertThatThrownBy(() -> service.rotate(null)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.rotate("  ")).isInstanceOf(UnauthorizedException.class);

        verify(repository, never()).findByTokenHash(any());
    }

    @Test
    void revocarLaFamiliaDeUnTokenConocidoLaCorta() {
        when(repository.findByTokenHash(RefreshTokenService.hash("mio")))
                .thenReturn(Optional.of(guardado("mio", AHORA.plus(Duration.ofDays(1)), null)));

        service.revokeFamilyOf("mio");

        verify(repository).revokeFamily(FAMILIA, AHORA);
    }

    @Test
    void revocarLaFamiliaDeUnTokenDesconocidoNoHaceNada() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        service.revokeFamilyOf("inventado");

        verify(repository, never()).revokeFamily(any(), any());
    }

    @Test
    void laLimpiezaBorraLosVencidosHastaAhora() {
        when(repository.deleteExpiredBefore(AHORA)).thenReturn(3);

        service.deleteExpired();

        verify(repository).deleteExpiredBefore(eq(AHORA));
    }

    @Test
    void elHashEsEstableYDistintoPorToken() {
        assertThat(RefreshTokenService.hash("a")).isEqualTo(RefreshTokenService.hash("a"));
        assertThat(RefreshTokenService.hash("a")).isNotEqualTo(RefreshTokenService.hash("b"));
    }
}
