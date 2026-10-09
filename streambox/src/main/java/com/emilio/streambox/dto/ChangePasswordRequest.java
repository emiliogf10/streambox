package com.emilio.streambox.dto;

import com.emilio.streambox.security.password.PasswordPolicy;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code PUT /api/users/me/password}: la contraseña actual (prueba
 * de que quien usa la sesión conoce la contraseña) y la nueva.
 *
 * <p>
 * <b>Qué se valida aquí y qué en el servicio.</b> Aquí solo la forma: que
 * vengan y su longitud. El resto de la {@link PasswordPolicy} (no común, como
 * mucho 72 bytes, sin el nombre de usuario ni el email) necesita los datos de
 * la cuenta, que no vienen en la petición (salen del token), así que lo
 * comprueba {@code PasswordChangeService}; el error llega igual, en
 * {@code validationErrors.newPassword}.
 * </p>
 *
 * <p>
 * <b>{@code currentPassword} no aplica la política</b>, igual que el login: una
 * cuenta creada con la política anterior (mínimo 8 caracteres) debe poder
 * cambiar su contraseña. Su máximo de {@value LoginRequest#MAX_PASSWORD_LENGTH}
 * caracteres es el del login: un tope contra cuerpos enormes que pasarían a
 * BCrypt, no una regla de la política.
 * </p>
 *
 * @param currentPassword contraseña actual de la cuenta
 * @param newPassword     contraseña nueva; debe cumplir la política y ser
 *                        distinta de la actual
 */
public record ChangePasswordRequest(

        @Schema(description = "Contraseña actual de la cuenta. Si no es correcta: 400 "
                + "CURRENT_PASSWORD_INCORRECT; tras 5 fallos en 15 minutos, 429.",
                example = "mi-contraseña-de-siempre", maxLength = LoginRequest.MAX_PASSWORD_LENGTH)
        @NotBlank(message = "La contraseña actual es obligatoria")
        @Size(max = LoginRequest.MAX_PASSWORD_LENGTH,
                message = "La contraseña no puede tener más de " + LoginRequest.MAX_PASSWORD_LENGTH + " caracteres")
        String currentPassword,

        @Schema(description = "Contraseña nueva: entre 12 y 64 caracteres y como máximo 72 bytes en UTF-8, "
                + "no común ni trivial, sin el nombre de usuario ni la parte del email anterior a la @, y "
                + "distinta de la actual.",
                example = "tres palabras al azar 2026",
                minLength = PasswordPolicy.MIN_LENGTH, maxLength = PasswordPolicy.MAX_LENGTH)
        @NotNull(message = PasswordPolicy.REQUIRED_MESSAGE)
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
                message = PasswordPolicy.LENGTH_MESSAGE)
        String newPassword) {

    /**
     * Las contraseñas no deben acabar en un log por un {@code toString()}
     * automático (el de un {@code record} imprime todos sus campos).
     *
     * @return descripción sin las contraseñas
     */
    @Override
    public String toString() {
        return "ChangePasswordRequest[currentPassword=***, newPassword=***]";
    }
}
