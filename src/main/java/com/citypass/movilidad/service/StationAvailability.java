package com.citypass.movilidad.service;

/**
 * Disponibilidad calculada de una estación. Concentra en un único lugar la regla de negocio
 * de MOV-016, para que la consulta por estación y la búsqueda de estaciones cercanas
 * (MOV-017) no puedan desincronizarse.
 */
public record StationAvailability(int availableBikes, int availableSlots) {

    /**
     * @param capacity       anclajes declarados de la estación
     * @param bikesAtStation bicicletas presentes en cualquier estado: todas ocupan anclaje
     * @param availableBikes bicicletas AVAILABLE: las únicas que se pueden retirar
     */
    public static StationAvailability of(int capacity, long bikesAtStation, long availableBikes) {
        // Nunca negativo: el dataset importado puede traer más bicicletas que anclajes declarados.
        int freeSlots = (int) Math.max(0L, capacity - bikesAtStation);
        return new StationAvailability((int) availableBikes, freeSlots);
    }

    /** Estación sin bicicletas: todos los anclajes libres, nada para retirar. */
    public static StationAvailability empty(int capacity) {
        return new StationAvailability(0, Math.max(0, capacity));
    }
}
