package com.emilio.streambox.exception;

/**
 * El token de «He olvidado mi contraseña» no sirve: no existe, ha caducado, ya
 * se usó (o se invalidó al pedir otro enlace o al cambiar la contraseña) o ni
 * siquiera tiene el formato de un token.
 *
 * <p>
 * {@code GlobalExceptionHandler} la convierte en un 400
 * {@code INVALID_RESET_TOKEN} con un único mensaje para todos los casos:
 * distinguirlos no ayuda al usuario (en todos tiene que pedir otro enlace) y
 * sí a quien prueba tokens (sabría cuáles existieron). Es una excepción propia
 * y no {@link InvalidVerificationTokenException} porque la salida que ofrece la
 * interfaz es otra: pedir un enlace de restablecimiento, no de confirmación.
 * </p>
 */
public class InvalidResetTokenException extends RuntimeException {

    /** Mensaje único (en español, para mostrarlo tal cual). */
    public static final String MESSAGE = "El enlace para restablecer la contraseña no es válido o ha caducado.";

    /**
     * Crea la excepción con el mensaje único.
     */
    public InvalidResetTokenException() {
        super(MESSAGE);
    }
}
