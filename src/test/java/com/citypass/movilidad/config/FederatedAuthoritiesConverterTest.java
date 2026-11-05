package com.citypass.movilidad.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Mapeo de los grupos LDAP crudos a authorities y al rol único de la tabla roles. */
class FederatedAuthoritiesConverterTest {

    private static final Map<String, String> GRUPOS = Map.of(
            "admin", "ADMIN",
            "delegados", "ADMIN",
            "operadores", "USER",
            "soporte-n2", "USER");

    private static FederatedAuthoritiesConverter converterDe(AuthProperties props) {
        return new FederatedAuthoritiesConverter(props, new FederatedRoleResolver(props));
    }

    private final AuthProperties props = properties(GRUPOS);
    private final FederatedAuthoritiesConverter converter = converterDe(props);
    private final FederatedRoleResolver roleResolver = new FederatedRoleResolver(props);

    private static AuthProperties properties(Map<String, String> groupRoles) {
        return new AuthProperties(
                "https://api.citypass-login.pp.ua/.well-known/jwks.json",
                "https://idp.citypass.local", "citypass-movilidad-api", "human",
                groupRoles, "USER", List.of("ADMIN", "USER"),
                List.of(), List.of(), 1L);
    }

    private static Jwt tokenConGrupos(Object groups) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("U000042");
        if (groups != null) {
            builder.claim("groups", groups);
        }
        return builder.build();
    }

    @Test
    void mapeaUnGrupoAlRolConfigurado() {
        assertThat(authorities(tokenConGrupos(List.of("admin"))))
                .contains("ROLE_ADMIN", "GROUP_admin");
    }

    /**
     * El personal técnico queda con el rol por defecto y nada más. Se afirma con containsExactly
     * a propósito: lo que importa es que no aparezca ningún rol elevado.
     */
    @Test
    void elPersonalTecnicoNoRecibeRolElevado() {
        assertThat(authorities(tokenConGrupos(List.of("operadores"))))
                .containsExactly("ROLE_USER", "GROUP_operadores");
    }

    /** Emite el GROUP_ aunque el grupo no esté mapeado a ningún rol. */
    @Test
    void emiteElGrupoCrudoAunqueNoEsteMapeado() {
        assertThat(authorities(tokenConGrupos(List.of("inspectores"))))
                .contains("GROUP_inspectores")
                .doesNotContain("ROLE_INSPECTORES");
    }

    @Test
    void siempreOtorgaElRolPorDefecto() {
        assertThat(authorities(tokenConGrupos(List.of("admin")))).contains("ROLE_USER", "ROLE_ADMIN");
    }

    /** El spec avisa que groups puede venir vacío: el token sigue siendo válido. */
    @Test
    void unTokenConGruposVaciosRecibeSoloElRolPorDefecto() {
        assertThat(authorities(tokenConGrupos(List.of()))).containsExactly("ROLE_USER");
        assertThat(roleResolver.resolveRole(tokenConGrupos(List.of()))).isEqualTo("USER");
    }

    @Test
    void unTokenSinElClaimGroupsRecibeSoloElRolPorDefecto() {
        assertThat(authorities(tokenConGrupos(null))).containsExactly("ROLE_USER");
    }

    /** El casing que entrega LDAP no es confiable, así que el mapeo compara en minúsculas. */
    @Test
    void elMapeoIgnoraMayusculasYEspacios() {
        assertThat(authorities(tokenConGrupos(List.of("  Admin  ")))).contains("ROLE_ADMIN");
    }

    @Test
    void ignoraLasEntradasEnBlanco() {
        assertThat(authorities(tokenConGrupos(Arrays.asList("", "   ", "admin"))))
                .containsExactly("ROLE_USER", "GROUP_admin", "ROLE_ADMIN");
    }

    /** users.role es de un solo valor: con varios grupos gana el primero de role-precedence. */
    @Test
    void conVariosGruposElRolUnicoLoDefineLaPrecedencia() {
        assertThat(roleResolver.resolveRole(tokenConGrupos(List.of("operadores", "admin"))))
                .isEqualTo("ADMIN");
        assertThat(roleResolver.resolveRole(tokenConGrupos(List.of("admin", "operadores"))))
                .isEqualTo("ADMIN");
    }

    @Test
    void conUnSoloGrupoMapeadoElRolEsElMapeado() {
        assertThat(roleResolver.resolveRole(tokenConGrupos(List.of("delegados")))).isEqualTo("ADMIN");
    }

    @Test
    void unGrupoNoMapeadoCaeAlRolPorDefecto() {
        assertThat(roleResolver.resolveRole(tokenConGrupos(List.of("inspectores")))).isEqualTo("USER");
    }

    /** Sin mapeo configurado, todos los tokens caen al rol por defecto. */
    @Test
    void sinMapeoConfiguradoTodoCaeAlRolPorDefecto() {
        FederatedRoleResolver sinMapeo = new FederatedRoleResolver(properties(Map.of()));
        assertThat(sinMapeo.resolveRole(tokenConGrupos(List.of("admin")))).isEqualTo("USER");
    }

    private List<String> authorities(Jwt jwt) {
        return converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
    }
}
