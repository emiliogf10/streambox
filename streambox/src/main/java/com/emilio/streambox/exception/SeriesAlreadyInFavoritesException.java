package com.emilio.streambox.exception;

/**
 * Excepción utilizada cuando se intenta añadir a "Mi lista" una serie que ya
 * está en ella (HTTP 409, {@code SERIES_ALREADY_IN_FAVORITES}).
 *
 * <p>
 * Tiene su propio código (y no reutiliza el de películas) para que el cliente
 * pueda distinguirlos sin mirar la ruta ni el texto del mensaje.
 * </p>
 */
public class SeriesAlreadyInFavoritesException extends RuntimeException {

    /**
     * Crea la excepción con un mensaje descriptivo.
     *
     * @param message mensaje descriptivo del error
     */
    public SeriesAlreadyInFavoritesException(String message) {
        super(message);
    }
}
