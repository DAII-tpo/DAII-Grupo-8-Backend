package com.citypass.movilidad.eventbus;

import java.time.Instant;

/**
 * Metadata que el gateway estampa en cada evento. Ninguno de estos campos lo escribe quien publica.
 *
 * @param eventId     estable entre reentregas: es la clave para descartar duplicados
 * @param eventType   fqn del event type
 * @param receivedAt  reloj del gateway, no del que publicó
 * @param source      sub del token de servicio de quien publicó (no se puede falsificar)
 * @param payloadHash SHA-256 de data
 */
public record EventMetadata(
        String eventId,
        String eventType,
        Instant receivedAt,
        String source,
        String tokenId,
        Integer schemaId,
        String payloadHash,
        String gatewayVersion,
        String instanceId) {
}
