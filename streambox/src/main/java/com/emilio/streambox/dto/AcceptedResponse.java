package com.emilio.streambox.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Respuesta 202 de las operaciones que contestan <b>siempre lo mismo</b> para
 * no revelar qué emails tienen cuenta: el registro ({@code POST /api/users}),
 * el reenvío del correo de confirmación
 * ({@code POST /api/auth/email-verification/resend}) y la petición del enlace
 * de «He olvidado mi contraseña» ({@code POST /api/auth/password-reset}).
 *
 * <p>
 * El texto es fijo para cada operación (constantes de abajo) y no depende de
 * nada de la cuenta: si cambiara según el caso, el cuerpo delataría lo que el
 * código de estado oculta.
 * </p>
 *
 * @param message texto fijo en español
 */
@Schema(description = "Respuesta 202 que es idéntica exista o no una cuenta con ese correo (para no revelar "
        + "quién está registrado).")
public record AcceptedResponse(
        @Schema(description = "Texto fijo en español, igual en todos los casos.",
                example = AcceptedResponse.REGISTRATION_MESSAGE,
                requiredMode = Schema.RequiredMode.REQUIRED)
        String message) {

    /** Texto del registro. */
    public static final String REGISTRATION_MESSAGE =
            "Si el correo es correcto, te llegará un enlace para confirmar tu cuenta.";

    /** Texto del reenvío del correo de confirmación. */
    public static final String RESEND_MESSAGE =
            "Si hay una cuenta pendiente de confirmar con ese correo, te llegará un enlace nuevo.";

    /**
     * Texto de «He olvidado mi contraseña». Dice «si hay una cuenta» y no
     * «te hemos enviado»: con un email sin cuenta (o pasado el límite
     * silencioso) no se envía nada y la respuesta es la misma.
     */
    public static final String PASSWORD_RESET_MESSAGE =
            "Si hay una cuenta con ese correo, te llegará un enlace para elegir una contraseña nueva.";

    /** Respuesta del registro. */
    public static final AcceptedResponse REGISTRATION = new AcceptedResponse(REGISTRATION_MESSAGE);

    /** Respuesta del reenvío. */
    public static final AcceptedResponse RESEND = new AcceptedResponse(RESEND_MESSAGE);

    /** Respuesta de la petición del enlace para restablecer la contraseña. */
    public static final AcceptedResponse PASSWORD_RESET = new AcceptedResponse(PASSWORD_RESET_MESSAGE);
}
