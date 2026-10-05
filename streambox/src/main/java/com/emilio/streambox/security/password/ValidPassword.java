package com.emilio.streambox.security.password;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Restricción de Bean Validation, a nivel de clase, que aplica a la contraseña
 * las reglas de contenido de {@link PasswordPolicy}: como máximo
 * {@value PasswordPolicy#MAX_BYTES} bytes, no ser común ni trivial y no
 * contener el nombre de usuario ni la parte local del email.
 *
 * <p>
 * <b>Por qué a nivel de clase.</b> Una restricción sobre el campo
 * {@code password} solo ve la contraseña; para saber si contiene el nombre de
 * usuario o el email necesita el objeto entero. El error se asocia aun así al
 * campo {@code password}, así que el cliente lo recibe en
 * {@code validationErrors.password} como cualquier otro.
 * </p>
 *
 * <p>
 * <b>Qué no comprueba.</b> La obligatoriedad y la longitud se dejan a
 * {@code @NotNull} y {@code @Size} sobre el campo (springdoc los traduce a
 * {@code required}, {@code minLength} y {@code maxLength} en el OpenAPI). Si la
 * contraseña es {@code null} o tiene una longitud fuera de rango, este
 * validador la da por buena para que el campo reciba un único mensaje.
 * </p>
 *
 * <p>
 * Solo se puede poner en clases que implementen {@link NewPasswordRequest}.
 * </p>
 */
@Documented
@Constraint(validatedBy = ValidPasswordValidator.class)
@Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

    /**
     * Mensaje por defecto. No se usa en la práctica: el validador sustituye la
     * violación por la de la regla concreta incumplida.
     *
     * @return mensaje genérico de la restricción
     */
    String message() default "La contraseña no cumple la política de seguridad";

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
