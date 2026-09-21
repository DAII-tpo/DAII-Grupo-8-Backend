package com.citypass.movilidad.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DomainValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    record CoordinateSample(@Latitude Double latitude, @Longitude Double longitude) {
    }

    record EntityIdSample(@EntityId Long id) {
    }

    @Test
    void acceptsValidCoordinates() {
        CoordinateSample sample = new CoordinateSample(-34.6037, -58.3816);
        Set<ConstraintViolation<CoordinateSample>> violations = validator.validate(sample);
        assertThat(violations).isEmpty();
    }

    @Test
    void acceptsExtremeBoundaryCoordinates() {
        CoordinateSample sampleMax = new CoordinateSample(90.0, 180.0);
        assertThat(validator.validate(sampleMax)).isEmpty();

        CoordinateSample sampleMin = new CoordinateSample(-90.0, -180.0);
        assertThat(validator.validate(sampleMin)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(doubles = {90.0001, 91.0, 180.0, -90.0001, -120.0})
    void rejectsInvalidLatitude(double invalidLat) {
        CoordinateSample sample = new CoordinateSample(invalidLat, -58.3816);
        Set<ConstraintViolation<CoordinateSample>> violations = validator.validate(sample);
        assertThat(violations).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(doubles = {180.0001, 190.0, -180.0001, -200.0})
    void rejectsInvalidLongitude(double invalidLng) {
        CoordinateSample sample = new CoordinateSample(-34.6037, invalidLng);
        Set<ConstraintViolation<CoordinateSample>> violations = validator.validate(sample);
        assertThat(violations).hasSize(1);
    }

    @Test
    void acceptsValidEntityId() {
        EntityIdSample sample = new EntityIdSample(1L);
        assertThat(validator.validate(sample)).isEmpty();

        EntityIdSample largeSample = new EntityIdSample(999999L);
        assertThat(validator.validate(largeSample)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, -99L})
    void rejectsZeroOrNegativeEntityId(long invalidId) {
        EntityIdSample sample = new EntityIdSample(invalidId);
        Set<ConstraintViolation<EntityIdSample>> violations = validator.validate(sample);
        assertThat(violations).hasSize(1);
    }
}
