package com.emilio.streambox.dto;

import com.emilio.streambox.validation.ControlCharacters;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /api/auth/email-verification/resend}: el email al que
 * se pide otro enlace de confirmación.
 *
 * <p>
 * Mismas reglas y mensajes que el email del registro y del login: un email
 * mal formado es un 400 {@code VALIDATION_ERROR}, que no revela nada (depende
 * solo del texto enviado, no de si existe la cuenta). Incluido el rechazo de
 * los caracteres de control, que {@code @Email} deja pasar en parte (ver
 * {@code CreateUserRequest#getEmail}).
 * </p>
 *
 * @param email correo de la cuenta pendiente
 */
@Schema(description = "Correo al que se pide otro enlace de confirmación.")
public record ResendVerificationRequest(
        @Schema(description = "Correo de la cuenta (como mucho 100 caracteres).", example = "ana@example.com")
        @Email(message = CreateUserRequest.EMAIL_FORMAT_MESSAGE)
        @Pattern(regexp = ControlCharacters.SINGLE_LINE_TEXT, message = CreateUserRequest.EMAIL_FORMAT_MESSAGE)
        @NotBlank(message = CreateUserRequest.EMAIL_REQUIRED_MESSAGE)
        @Size(max = CreateUserRequest.EMAIL_MAX_LENGTH, message = CreateUserRequest.EMAIL_SIZE_MESSAGE)
        String email) {
}
