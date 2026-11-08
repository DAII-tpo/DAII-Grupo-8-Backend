package com.citypass.movilidad.client;

/**
 * Perfil de una persona en el IdP: justo lo que el access token no trae.
 *
 * <p>Cualquier componente puede ser nulo si el IdP no lo informó.
 */
public record FederatedUserInfo(String email, String firstName, String lastName) {
}
