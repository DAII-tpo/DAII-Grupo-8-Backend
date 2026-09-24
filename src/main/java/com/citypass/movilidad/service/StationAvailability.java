package com.citypass.movilidad.service;

/** Bicis disponibles y anclajes libres de una estación. Única implementación de esta regla. */
public record StationAvailability(int availableBikes, int availableSlots) {

    /** Anclajes libres = capacidad - bicis presentes (cualquier estado); disponibles = solo AVAILABLE. */
    public static StationAvailability of(int capacity, long bikesAtStation, long availableBikes) {
        // Nunca negativo, aunque haya más bicis que anclajes declarados.
        int freeSlots = (int) Math.max(0L, capacity - bikesAtStation);
        return new StationAvailability((int) availableBikes, freeSlots);
    }

    /** Estación sin bicis: todos los anclajes libres. */
    public static StationAvailability empty(int capacity) {
        return new StationAvailability(0, Math.max(0, capacity));
    }
}
