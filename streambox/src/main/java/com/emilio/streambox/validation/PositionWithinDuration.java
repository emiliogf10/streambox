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
 * {@link com.emilio.streambox.dto.WatchProgressRequest}: la posición del
 * reproductor no puede ser mayor que la duración del vídeo.
 *
 * <p>
 * <b>Por qué a nivel de clase.</b> La regla compara dos campos, y una
 * restricción de campo solo ve el suyo. Igual que {@link ValidSeriesYears}, el
 * error se cuelga del campo que hay que corregir ({@code positionSeconds}), así
 * que llega al cliente en {@code validationErrors.positionSeconds} y no con el
 * nombre de un método {@code @AssertTrue}.
 * </p>
 *
 * <p>
 * Es la misma regla que los {@code CHECK} {@code ck_*_position_within_duration}
 * de la {@code V14}: comprobarla aquí da un 400 claro en vez de una violación
 * de la base de datos (que acabaría en un 500).
 * </p>
 */
@Documented
@Constraint(validatedBy = PositionWithinDurationValidator.class)
@Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface PositionWithinDuration {

    /**
     * Mensaje de error que recibe el cliente en {@code validationErrors.positionSeconds}.
     *
     * @return mensaje de error
     */
    String message() default "La posición no puede ser mayor que la duración";

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
