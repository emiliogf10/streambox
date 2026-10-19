package com.emilio.streambox.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cuerpo de {@code POST /api/auth/email-verification}: el token del enlace del
 * correo.
 *
 * <p>
 * <b>Sin anotaciones de validación, a propósito.</b> Un token vacío, demasiado
 * largo o con caracteres imposibles recibe el mismo 400
 * {@code INVALID_VERIFICATION_TOKEN} que uno caducado o ya usado (lo comprueba
 * {@code EmailVerificationService}). Con {@code @NotBlank} o {@code @Size}
 * daría un {@code VALIDATION_ERROR} distinto, y la interfaz tendría que tratar
 * dos errores para decir lo mismo: «el enlace no vale».
 * </p>
 *
 * @param token token del enlace (Base64URL, 43 caracteres)
 */
@Schema(description = "Token del enlace del correo de confirmación.")
public record EmailVerificationRequest(
        @Schema(description = "Token que va tras #token= en el enlace del correo: 43 caracteres de Base64URL "
                + "(A-Z, a-z, 0-9, - y _).", example = "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0iY")
        String token) {
}
