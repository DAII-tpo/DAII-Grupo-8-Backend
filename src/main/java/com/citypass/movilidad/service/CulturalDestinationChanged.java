package com.citypass.movilidad.service;

/** Señal de un cambio cultural confirmado en DB; se procesa después del commit Kafka. */
public record CulturalDestinationChanged(String eventId) { }
