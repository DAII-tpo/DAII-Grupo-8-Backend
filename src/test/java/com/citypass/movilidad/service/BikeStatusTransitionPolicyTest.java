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

    @Test
    void rejectsNullStatuses() {
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(null, BikeStatus.AVAILABLE)).isFalse();
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(BikeStatus.AVAILABLE, null)).isFalse();
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(null, null)).isFalse();
    }

    @Test
    void rejectsTransitionToSameStatus() {
        for (BikeStatus status : BikeStatus.values()) {
            assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(status, status)).isFalse();
        }
    }

    @Test
    void validatesOutOfServiceAllowedAndForbiddenTransitions() {
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.OUT_OF_SERVICE, BikeStatus.MAINTENANCE)).isTrue();
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.OUT_OF_SERVICE, BikeStatus.STOLEN)).isTrue();
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.OUT_OF_SERVICE, BikeStatus.AVAILABLE)).isFalse();
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.OUT_OF_SERVICE, BikeStatus.IN_USE)).isFalse();
    }

    @Test
    void validatesStolenAllowedAndForbiddenTransitions() {
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.STOLEN, BikeStatus.MAINTENANCE)).isTrue();
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.STOLEN, BikeStatus.OUT_OF_SERVICE)).isTrue();
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.STOLEN, BikeStatus.AVAILABLE)).isFalse();
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.STOLEN, BikeStatus.IN_USE)).isFalse();
    }

    @Test
    void validatesMaintenanceAllowedAndForbiddenTransitions() {
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.MAINTENANCE, BikeStatus.AVAILABLE)).isTrue();
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.MAINTENANCE, BikeStatus.OUT_OF_SERVICE)).isTrue();
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.MAINTENANCE, BikeStatus.STOLEN)).isTrue();
        assertThat(BikeStatusTransitionPolicy.isAllowedForAdministration(
                BikeStatus.MAINTENANCE, BikeStatus.IN_USE)).isFalse();
    }
}
