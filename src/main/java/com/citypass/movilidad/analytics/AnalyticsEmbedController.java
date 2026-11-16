package com.citypass.movilidad.analytics;

import com.citypass.movilidad.config.AuthProperties;
import com.citypass.movilidad.exception.ForbiddenOperationException;
import com.citypass.movilidad.exception.UnauthorizedException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/analytics")
public class AnalyticsEmbedController {
    private final AnalyticsEmbedClient client;
    private final AuthProperties auth;

    public AnalyticsEmbedController(AnalyticsEmbedClient client, AuthProperties auth) {
        this.client = client;
        this.auth = auth;
    }

    @GetMapping("/embed")
    public ResponseEntity<byte[]> embed(Authentication authentication) {
        requireAdministrator(authentication);
        var jwt = (Jwt) authentication.getPrincipal();
        var embed = client.fetch(jwt.getTokenValue());
        return ResponseEntity.ok().headers(headers -> headers.addAll(embed.headers()))
                .contentType(embed.type()).body(embed.body());
    }

    /** Se ejecuta también cuando el modo local desactiva @PreAuthorize. Nunca usa el usuario demo. */
    private void requireAdministrator(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new UnauthorizedException("Iniciá sesión como administrador para consultar Analítica.");
        }
        if ("service".equals(jwt.getClaimAsString("token_use"))
                || !auth.issuer().equals(jwt.getClaimAsString("iss"))
                || !auth.requiredTokenUse().equals(jwt.getClaimAsString("token_use"))
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            throw new ForbiddenOperationException("Analítica está disponible exclusivamente para administradores.");
        }
    }
}
