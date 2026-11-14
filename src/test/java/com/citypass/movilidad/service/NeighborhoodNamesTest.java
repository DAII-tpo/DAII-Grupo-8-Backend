package com.citypass.movilidad.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NeighborhoodNamesTest {
    @Test
    void normalizesNamesFromClimateEvents() {
        assertEquals("PALERMO", NeighborhoodNames.normalize(" Palermo "));
        assertEquals("NUNEZ", NeighborhoodNames.normalize("NUÑEZ"));
        assertEquals("VILLA GRAL MITRE", NeighborhoodNames.normalize(" Villa  Gral.   Mitre "));
        assertEquals("SAN NICOLAS", NeighborhoodNames.normalize("San Nicolás"));
    }
}
