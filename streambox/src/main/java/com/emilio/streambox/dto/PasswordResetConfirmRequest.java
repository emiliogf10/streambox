package com.emilio.streambox.dto;

import com.emilio.streambox.security.password.PasswordPolicy;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /api/auth/password-reset/confirm}: el token del enlace
 * del correo y la contraseña nueva.
 *
 * <p>
 * <b>El token no lleva anotaciones de validación, a propósito</b> (como en
 * {@link EmailVerificationRequest}). Uno vacío, demasiado largo o con
 * caracteres imposibles recibe el mismo 400 {@code INVALID_RESET_TOKEN} que
 * uno caducado o ya usado (lo comprueba {@code PasswordResetService} sin ir a
 * la base de datos). Con {@code @NotBlank} o {@code @Size} daría un
 * {@code VALIDATION_ERROR} distinto, y la interfaz tendría que tratar dos
 * errores para decir lo mismo: «el enlace no vale». Un token enorme no cuesta
 * nada: la comprobación de formato se detiene en el carácter 44.
 * </p>
 *
 * <p>
 * <b>La contraseña nueva, en dos sitios.</b> Aquí solo su forma (que venga y
 * su longitud). El resto de la {@link PasswordPolicy} (no común, como mucho 72
 * bytes, sin el nombre de usuario ni el email) necesita los datos de la cuenta
 * dueña del token, que no vienen en la petición, así que lo comprueba el
 * servicio; el error llega igual, en {@code validationErrors.newPassword}, y en
 * ninguno de los dos casos se gasta el token.
 * </p>
 *
 * @param token       token del enlace (Base64URL, 43 caracteres)
 * @param newPassword contraseña nueva; debe cumplir la política
 */
@Schema(description = "Token del enlace de «He olvidado mi contraseña» y la contraseña nueva.")
public record PasswordResetConfirmRequest(

        @Schema(description = "Token que va tras #token= en el enlace del correo: 43 caracteres de Base64URL "
                + "(A-Z, a-z, 0-9, - y _).", example = "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0iY")
        String token,

        @Schema(description = "Contraseña nueva: entre 12 y 64 caracteres y como máximo 72 bytes en UTF-8, "
                + "no común ni trivial y sin el nombre de usuario ni la parte del email anterior a la @ de la "
                + "cuenta del enlace.",
                example = "tres palabras al azar 2026",
                minLength = PasswordPolicy.MIN_LENGTH, maxLength = PasswordPolicy.MAX_LENGTH)
        @NotNull(message = PasswordPolicy.REQUIRED_MESSAGE)
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
                message = PasswordPolicy.LENGTH_MESSAGE)
        String newPassword) {

    /**
     * Ni el token (da acceso a la cuenta) ni la contraseña deben acabar en un
     * log por un {@code toString()} automático (el de un {@code record}
     * imprime todos sus campos).
     *
     * @return descripción sin los datos secretos
     */
    @Override
    public String toString() {
        return "PasswordResetConfirmRequest[token=***, newPassword=***]";
    }
}
