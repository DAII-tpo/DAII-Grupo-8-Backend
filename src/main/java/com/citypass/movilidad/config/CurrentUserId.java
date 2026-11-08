package com.citypass.movilidad.config;


import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Inyecta el id local de la persona autenticada, resuelto desde el {@code sub} del JWT federado.
 *
 * <p>Reemplaza al header {@code X-User-Id}, que era falsificable. Los controllers siguen
 * recibiendo un {@code Long}, así que las firmas de los servicios no cambian.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface CurrentUserId {
}
