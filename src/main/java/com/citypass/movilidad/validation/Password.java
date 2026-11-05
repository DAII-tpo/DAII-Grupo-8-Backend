package com.citypass.movilidad.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;

/**
 * Contraseña de un ciudadano al registrarse.
 *
 * <p>El máximo de 72 no es cosmético: <strong>BCrypt trunca en silencio arriba de 72 bytes</strong>,
 * así que aceptar más largo significaría que el resto no cuenta para nada.
 *
 * <p>{@code @ReportAsSingleViolation} colapsa los cuatro fallos en un mensaje: enumerar qué regla
 * puntual falló le da pistas gratis a quien prueba contraseñas.
 */
@Documented
@Target({FIELD, PARAMETER, METHOD, ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@ReportAsSingleViolation
@Size(min = 10, max = 72)
@Pattern.List({
        @Pattern(regexp = ".*[a-z].*"),
        @Pattern(regexp = ".*[A-Z].*"),
        @Pattern(regexp = ".*\\d.*"),
        @Pattern(regexp = "\\S+")
})
public @interface Password {

    String message() default "debe tener entre 10 y 72 caracteres, sin espacios, "
            + "con al menos una minúscula, una mayúscula y un número";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
