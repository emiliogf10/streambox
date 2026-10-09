package com.emilio.streambox.exception;

/**
 * Al cambiar la contraseña ({@code PUT /api/users/me/password}), la contraseña
 * actual enviada no es la de la cuenta.
 *
 * <p>
 * {@code GlobalExceptionHandler} la convierte en un 400
 * {@code CURRENT_PASSWORD_INCORRECT} con el mensaje en
 * {@code validationErrors.currentPassword}. No es un 401: quien la recibe sí
 * tiene una sesión válida, y un 401 haría que el frontend intentara renovarla
 * y acabara cerrándola (ver {@code ErrorCode#CURRENT_PASSWORD_INCORRECT}).
 * </p>
 */
public class CurrentPasswordIncorrectException extends RuntimeException {

    /** Campo del formulario al que se asocia el error. */
    public static final String FIELD = "currentPassword";

    /** Mensaje único para el usuario (no dice nada de la contraseña real). */
    public static final String MESSAGE = "La contraseña actual no es correcta";

    /**
     * Crea la excepción con el mensaje único {@link #MESSAGE}.
     */
    public CurrentPasswordIncorrectException() {
        super(MESSAGE);
    }
}
