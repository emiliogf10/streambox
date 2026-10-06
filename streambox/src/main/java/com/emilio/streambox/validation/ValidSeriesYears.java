package com.emilio.streambox.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Restricción de Bean Validation a nivel de clase para
 * {@link com.emilio.streambox.dto.SeriesRequest}: el año de finalización, si
 * se indica, no puede ser anterior al año de estreno.
 *
 * <p>
 * <b>Por qué a nivel de clase.</b> Una restricción de campo solo ve su propio
 * valor, y esta regla compara dos campos. Se podría usar un método
 * {@code @AssertTrue} en el {@code record}, pero el error saldría con el
 * nombre del método ({@code endYearValid}) en {@code validationErrors}; con
 * una restricción de clase el validador asigna el error al campo
 * {@code endYear}, que es el que el usuario tiene que corregir y el que el
 * formulario del frontend sabe señalar.
 * </p>
 *
 * <p>
 * Es la misma regla que la restricción {@code ck_series_end_year} de la base
 * de datos: comprobarla aquí evita que un dato incorrecto llegue a la base y
 * acabe en un 409 de integridad que no explica nada.
 * </p>
 */
@Documented
@Constraint(validatedBy = ValidSeriesYearsValidator.class)
@Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidSeriesYears {

    /**
     * Mensaje de error que recibe el cliente en {@code validationErrors.endYear}.
     *
     * @return mensaje de error
     */
    String message() default "El año de finalización no puede ser anterior al año de estreno";

    /**
     * Grupos de validación (estándar de Bean Validation; no se usan en el proyecto).
     *
     * @return grupos a los que pertenece la restricción
     */
    Class<?>[] groups() default {};

    /**
     * Metadatos adicionales (estándar de Bean Validation; no se usan en el proyecto).
     *
     * @return carga asociada a la restricción
     */
    Class<? extends Payload>[] payload() default {};
}
