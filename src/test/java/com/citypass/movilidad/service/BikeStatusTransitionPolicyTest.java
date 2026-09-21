package com.citypass.movilidad.service;

import com.citypass.movilidad.model.enums.BikeStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BikeStatusTransitionPolicyTest {

    @Test
    void acceptsDocumentedAdministrativeTransitions() {
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.AVAILABLE, BikeStatus.MAINTENANCE)).isTrue();
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.MAINTENANCE, BikeStatus.AVAILABLE)).isTrue();
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.STOLEN, BikeStatus.OUT_OF_SERVICE)).isTrue();
    }

    @Test
    void rejectsInUseAndDirectReturnToAvailable() {
        for (BikeStatus target : BikeStatus.values()) {
            assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(BikeStatus.IN_USE, target)).isFalse();
        }
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.OUT_OF_SERVICE, BikeStatus.AVAILABLE)).isFalse();
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.AVAILABLE, BikeStatus.IN_USE)).isFalse();
    }
}
