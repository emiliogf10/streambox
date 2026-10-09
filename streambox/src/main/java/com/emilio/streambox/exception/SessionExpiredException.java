package com.emilio.streambox.exception;

/**
 * El refresh token no permite renovar la sesión: falta, no existe, ha
 * caducado (él o su familia), está revocado o se ha reutilizado.
 *
 * <p>
 * {@code GlobalExceptionHandler} la convierte en un 401 {@code SESSION_EXPIRED}
 * con el mismo mensaje en todos los casos: distinguir «no existe» de
 * «revocado» o «reutilizado» solo serviría a quien prueba tokens robados. El
 * cliente solo necesita saber que debe volver a iniciar sesión.
 * </p>
 */
public class SessionExpiredException extends RuntimeException {

    /** Mensaje único de todos los casos. */
    public static final String MESSAGE = "La sesión ha caducado. Inicia sesión de nuevo.";

    /**
     * Crea la excepción con el mensaje único.
     */
    public SessionExpiredException() {
        super(MESSAGE);
    }
}
