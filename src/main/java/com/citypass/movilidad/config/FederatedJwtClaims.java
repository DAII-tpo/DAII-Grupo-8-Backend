package com.citypass.movilidad.config;

/** Claims propios del access token del Login Federado, fuera de los estándar de JWT. */
public final class FederatedJwtClaims {

    /** Distingue persona ({@code human}) de servicio ({@code service}). */
    public static final String TOKEN_USE = "token_use";

    /** Grupos LDAP crudos, sin prefijos. Puede venir vacío. */
    public static final String GROUPS = "groups";

    /** Nombre de usuario para mostrar en pantalla. No es clave: es mutable. */
    public static final String PREFERRED_USERNAME = "preferred_username";

    private FederatedJwtClaims() {
    }
}
