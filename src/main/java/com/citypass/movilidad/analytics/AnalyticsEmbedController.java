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
    public ResponseEntity<String> embed(Authentication authentication) {
        requireAdministrator(authentication);
        var embed = client.fetch();
        return ResponseEntity.ok().contentType(embed.type())
                .header("Cache-Control", "private, no-store")
                .header("Content-Security-Policy", AnalyticsHtml.POLICY)
                .header("Vary", "Authorization")
                .body(embed.body());
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
