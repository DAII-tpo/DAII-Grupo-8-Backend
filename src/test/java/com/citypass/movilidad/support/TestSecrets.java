package com.citypass.movilidad.support;

/**
 * Valores de prueba para las credenciales, en un solo lugar.
 *
 * <p>No son secretos: son fixtures de test que nunca existieron en ningún entorno. Centralizarlos
 * tiene dos razones concretas. Una, que repetirlos en cada clase deja al análisis estático
 * reportando un hotspot de credenciales por cada copia, y revisarlos de a diez es ruido. Otra, que
 * si mañana cambia la política de contraseñas, se toca una línea y no ocho.
 *
 * <p>Son constantes de compilación a propósito: así se pueden usar dentro de
 * {@code @SpringBootTest(properties = ...)}, que exige expresiones constantes.
 */
public final class TestSecrets {

    /**
     * Clave HS256 de prueba, base64url de 32 bytes.
     *
     * <p>El modo seguro exige una clave elegida: sin esto el contexto no arranca, que es
     * justamente la garantía que da {@code LocalSigningKey}.
     */
    public static final String CITIZEN_AUTH_SECRET =
            "c2VjcmV0by1kZS1wcnVlYmEtZGUtMzItYnl0ZXMhISE";

    /** La property completa, lista para la lista de un {@code @SpringBootTest}. */
    public static final String CITIZEN_AUTH_SECRET_PROPERTY =
            "movilidad.citizen-auth.secret=" + CITIZEN_AUTH_SECRET;

    /** Contraseña que cumple la política de {@code @Password}. */
    public static final String CITIZEN_PASSWORD = "Bicicleta2026";

    /** Una que no es la de nadie, para los caminos de credenciales incorrectas. */
    public static final String WRONG_PASSWORD = "EstaEstaMal123";

    /** Hash inventado: los tests que lo usan mockean el encoder, así que nunca se verifica. */
    public static final String FAKE_BCRYPT_HASH = "$2a$10$hashDeMentira";

    private TestSecrets() {
    }
}
