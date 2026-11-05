package com.citypass.movilidad.config;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;

/** Ajustes del procesador JOSE compartidos entre el decoder de producción y el de los tests. */
public final class FederatedJwtDecoders {

    /** {@code typ} que usan los access tokens según la RFC 9068. */
    private static final JOSEObjectType AT_JWT = new JOSEObjectType("at+jwt");

    private FederatedJwtDecoders() {
    }

    /**
     * Acepta el {@code typ: at+jwt} de los access tokens.
     *
     * <p>Es defensivo: hoy el IdP del Grupo 2 emite el header sin {@code typ} (solo {@code kid}
     * y {@code alg}), que Nimbus ya acepta por defecto. Pero por defecto solo admite {@code typ}
     * ausente o {@code JWT}, y lo rechaza en el procesador, antes de que corra cualquier
     * {@code OAuth2TokenValidator}: si el IdP pasara a mandar {@code at+jwt}, como manda la
     * RFC 9068 para access tokens, todos los requests morirían con un 401 opaco     ("JOSE header typ (type) at+jwt not allowed").
     */
    public static void acceptAccessTokenTypes(ConfigurableJWTProcessor<SecurityContext> processor) {
        processor.setJWSTypeVerifier(
                new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, AT_JWT, null));
    }
}
