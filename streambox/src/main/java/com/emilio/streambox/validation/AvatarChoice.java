package com.emilio.streambox.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Restricción de Bean Validation para el campo {@code avatar} de
 * {@code PATCH /api/users/me}: el valor JSON recibido debe ser {@code null}
 * (volver a la inicial) o exactamente una de las claves de {@link Avatar}.
 *
 * <p>
 * Se aplica a un {@code tools.jackson.databind.JsonNode} y no a un
 * {@code String} por dos motivos, explicados en {@code UpdateProfileRequest}:
 * distinguir «campo ausente» de «campo a {@code null}», y que un número o un
 * objeto reciban este mismo 400 {@code VALIDATION_ERROR} con su mensaje por
 * campo, en lugar del {@code MALFORMED_REQUEST} genérico que daría Jackson al
 * no poder convertirlos en texto ({@code StrictJsonScalarsConfig}).
 * </p>
 *
 * <p>
 * El campo ausente llega como {@code null} de Java y se da por válido: no
 * pide ningún cambio.
 * </p>
 */
@Documented
@Constraint(validatedBy = AvatarChoiceValidator.class)
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface AvatarChoice {

    /** Mensaje del 400 cuando el avatar no es de la lista (contrato con el frontend). */
    String MESSAGE = "Elige uno de los avatares disponibles";

    /**
     * Mensaje de error.
     *
     * @return mensaje que recibe el cliente en {@code validationErrors.avatar}
     */
    String message() default MESSAGE;

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
