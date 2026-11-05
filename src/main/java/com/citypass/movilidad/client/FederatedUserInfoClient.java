package com.citypass.movilidad.client;

import java.util.Optional;

/**
 * Trae del IdP el perfil de la persona autenticada.
 *
 * <p>Es una interfaz por dos razones: los tests enchufan un doble sin salir a la red, y la
 * ausencia del dato queda en el contrato. Nunca propaga el error del IdP.
 */
public interface FederatedUserInfoClient {

    /**
     * Perfil de quien presentó ese access token, o vacío si el IdP no lo pudo dar.
     *
     * @param accessToken el token tal como llegó, que es con lo que el IdP autentica la consulta
     * @param subject     el {@code sub}, con el que se recuerda no reintentar contra un IdP caído
     */
    Optional<FederatedUserInfo> fetch(String accessToken, String subject);
}
