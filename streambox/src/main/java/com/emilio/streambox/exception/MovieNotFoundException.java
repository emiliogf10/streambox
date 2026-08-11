package com.emilio.streambox.exception;

/**
 * Excepción utilizada cuando no se encuentra una película
 * con el identificador solicitado.
 *
 * <p>Esta excepción se utiliza para diferenciar la ausencia de
 * una película de otros errores internos de la aplicación.</p>
 */
public class MovieNotFoundException extends RuntimeException {

    /**
     * Crea una excepción indicando que una película no ha sido encontrada.
     *
     * @param message mensaje descriptivo del error
     */
    public MovieNotFoundException(String message) {
        super(message);
    }
}
