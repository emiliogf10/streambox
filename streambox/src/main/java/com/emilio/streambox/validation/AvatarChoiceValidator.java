package com.emilio.streambox.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import tools.jackson.databind.JsonNode;

/**
 * Valida {@link AvatarChoice}: el valor JSON del avatar debe ser {@code null}
 * o una cadena que sea exactamente una clave de {@link Avatar}.
 *
 * <p>
 * Tabla de decisión (lo que llega en el JSON → resultado):
 * </p>
 * <ul>
 * <li>campo ausente (Java {@code null}) → válido: no se pide cambio;</li>
 * <li>{@code null} JSON ({@code NullNode}) → válido: volver a la inicial;</li>
 * <li>cadena que es una clave de la lista → válido;</li>
 * <li>cualquier otra cosa: otra cadena (también {@code ""}, {@code "Cat"},
 * {@code " cat"} o una clave con formato válido que no está en la lista),
 * un número, un booleano, un objeto o un array → no válido.</li>
 * </ul>
 */
public class AvatarChoiceValidator implements ConstraintValidator<AvatarChoice, JsonNode> {

    /**
     * Comprueba el valor.
     *
     * @param value   valor JSON recibido, o {@code null} si el campo no venía
     * @param context contexto de validación (no se usa: el mensaje es el de la anotación)
     * @return {@code true} si el valor es aceptable
     */
    @Override
    public boolean isValid(JsonNode value, ConstraintValidatorContext context) {
        if (value == null || value.isNull()) {
            return true;
        }
        return value.isString() && Avatar.isKnownKey(value.stringValue());
    }
}
