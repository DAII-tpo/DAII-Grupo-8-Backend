package com.citypass.movilidad.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;

/**
 * Latitud geográfica válida. El rango se declara una sola vez acá y se reutiliza en DTOs y
 * parámetros de controller, en lugar de repetir @DecimalMin/@DecimalMax en cada uno (MOV-021).
 */
@Documented
@Target({FIELD, PARAMETER, METHOD, ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@ReportAsSingleViolation
@DecimalMin(value = "-90.0", message = "{jakarta.validation.constraints.DecimalMin.message}")
@DecimalMax(value = "90.0", message = "{jakarta.validation.constraints.DecimalMax.message}")
public @interface Latitude {

    String message() default "debe ser una latitud entre -90.0 y 90.0";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
