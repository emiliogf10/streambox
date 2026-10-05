package com.emilio.streambox.exception;

/**
 * Excepción lanzada cuando las credenciales proporcionadas
 * durante la autenticación no son válidas.
 *
 * <p>
 * Puede llevar los intentos que quedan antes de bloquear la cuenta, que
 * {@code GlobalExceptionHandler} devuelve en {@code remainingAttempts}. El
 * número depende solo del email (exista o no la cuenta), así que no revela
 * qué emails están registrados.
 * </p>
 */
public class InvalidCredentialsException extends RuntimeException {

    private final Integer remainingAttempts;

    /**
     * Crea una excepción indicando que las credenciales
     * proporcionadas no son correctas, sin información de intentos.
     *
     * @param message mensaje descriptivo del error
     */
    public InvalidCredentialsException(String message) {
        this(message, null);
    }

    /**
     * Crea una excepción con los intentos de login restantes.
     *
     * @param message           mensaje descriptivo del error
     * @param remainingAttempts intentos que le quedan a la cuenta con este
     *                          fallo ya descontado (&ge; 1; ver
     *                          {@code ErrorResponse}), o {@code null} si no aplica
     */
    public InvalidCredentialsException(String message, Integer remainingAttempts) {
        super(message);
        this.remainingAttempts = remainingAttempts;
    }

    /**
     * @return intentos restantes antes del bloqueo, o {@code null} si no se conocen
     */
    public Integer getRemainingAttempts() {
        return remainingAttempts;
    }
}
