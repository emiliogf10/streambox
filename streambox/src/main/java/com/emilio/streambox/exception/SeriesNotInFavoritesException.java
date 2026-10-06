package com.emilio.streambox.exception;

/**
 * Excepción utilizada cuando se intenta quitar de "Mi lista" una serie que no
 * está en ella (HTTP 404, {@code SERIES_NOT_IN_FAVORITES}).
 *
 * <p>
 * Hereda de {@link ResourceNotFoundException} porque es un 404, pero
 * {@link GlobalExceptionHandler} tiene un manejador propio para ella (Spring
 * elige el de la clase más concreta) que responde con su código específico en
 * lugar de {@code RESOURCE_NOT_FOUND}: así el cliente distingue "la serie no
 * existe" de "existe pero no estaba en tu lista". Mismo diseño que
 * {@link MovieNotInFavoritesException}.
 * </p>
 */
public class SeriesNotInFavoritesException extends ResourceNotFoundException {

    /**
     * Crea la excepción con un mensaje descriptivo.
     *
     * @param message mensaje descriptivo del error
     */
    public SeriesNotInFavoritesException(String message) {
        super(message);
    }
}
