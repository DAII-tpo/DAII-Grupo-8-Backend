package com.citypass.movilidad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PipelineFailureTest {

    @Test
    void pipelineDebeFallarSiUnTestFalla() {
        assertEquals(1, 1);
    }
}