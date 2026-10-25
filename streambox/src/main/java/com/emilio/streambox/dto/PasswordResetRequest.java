package com.emilio.streambox.dto;

import com.emilio.streambox.validation.ControlCharacters;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /api/auth/password-reset}: el email de la cuenta a la
 * que se pide el enlace de «He olvidado mi contraseña».
 *
 * <p>
 * Mismas reglas y mensajes que el email del registro, del login y del reenvío
 * de la verificación ({@link ResendVerificationRequest}): un email mal formado
 * es un 400 {@code VALIDATION_ERROR}, que no revela nada porque depende solo
 * del texto enviado, no de si existe la cuenta.
 * </p>
 *
 * @param email correo de la cuenta
 */
@Schema(description = "Correo de la cuenta a la que se pide el enlace para elegir una contraseña nueva.")
public record PasswordResetRequest(
        @Schema(description = "Correo de la cuenta (como mucho 100 caracteres).", example = "ana@example.com")
        @Email(message = CreateUserRequest.EMAIL_FORMAT_MESSAGE)
        @Pattern(regexp = ControlCharacters.SINGLE_LINE_TEXT, message = CreateUserRequest.EMAIL_FORMAT_MESSAGE)
        @NotBlank(message = CreateUserRequest.EMAIL_REQUIRED_MESSAGE)
        @Size(max = CreateUserRequest.EMAIL_MAX_LENGTH, message = CreateUserRequest.EMAIL_SIZE_MESSAGE)
        String email) {
}
