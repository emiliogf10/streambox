package com.emilio.streambox.exception;

/**
 * El token de verificación del correo no sirve: no existe, ha caducado, ya se
 * usó (o se invalidó al pedir otro) o ni siquiera tiene el formato de un token.
 *
 * <p>
 * {@code GlobalExceptionHandler} la convierte en un 400
 * {@code INVALID_VERIFICATION_TOKEN} con un único mensaje para todos los
 * casos: distinguirlos no ayuda al usuario (en todos tiene que pedir otro
 * enlace) y sí a quien prueba tokens (sabría cuáles existieron).
 * </p>
 */
public class InvalidVerificationTokenException extends RuntimeException {

    /** Mensaje único (en español, para mostrarlo tal cual). */
    public static final String MESSAGE = "El enlace de confirmación no es válido o ha caducado.";

    /**
     * Crea la excepción con el mensaje único.
     */
    public InvalidVerificationTokenException() {
        super(MESSAGE);
    }
}
