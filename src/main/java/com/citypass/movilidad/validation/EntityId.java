package com.citypass.movilidad.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Positive;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;

/**
 * Identificador de una entidad: los IDs son autoincrementales, así que cualquier valor menor o
 * igual a cero es una solicitud mal formada y se rechaza con 400 antes de tocar la base, en
 * lugar de buscarlo y devolver un 404 engañoso (MOV-021).
 *
 * No implica que el ID exista: eso lo resuelve el service con ResourceNotFoundException.
 */
@Documented
@Target({FIELD, PARAMETER, METHOD, ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@ReportAsSingleViolation
@Positive
public @interface EntityId {

    String message() default "debe ser un identificador positivo";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
